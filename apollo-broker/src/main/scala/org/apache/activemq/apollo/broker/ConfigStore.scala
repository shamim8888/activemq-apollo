/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.broker

import org.apache.activemq.apollo.broker.jaxb.PropertiesReader
import org.apache.activemq.apollo.dto.{XmlCodec, ConnectorDTO, VirtualHostDTO, BrokerDTO}
import java.util.regex.Pattern
import javax.xml.stream.{XMLOutputFactory, XMLInputFactory}
import org.fusesource.hawtdispatch._
import _root_.org.fusesource.hawtdispatch.ScalaDispatchHelpers._
import java.util.concurrent.{TimeUnit, ExecutorService, Executors}
import org.fusesource.hawtbuf.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.xml.bind.{Marshaller, JAXBContext}
import java.io.{OutputStreamWriter, File}
import XmlCodec._
import org.apache.activemq.apollo.util._
import scala.util.continuations._
import org.fusesource.hawtdispatch.DispatchQueue

object ConfigStore {

  var store:ConfigStore = null

  def apply():ConfigStore = store

  def sync[T] (func: ConfigStore=>T):T = store.dispatchQueue.sync {
    func(store)
  }

  def update(value:ConfigStore) = store=value

}

/**
 * <p>
 * Defines an interface to access and update persistent broker configurations.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
trait ConfigStore extends Service {

  def listBrokers: List[String]

  def getBroker(id:String, eval:Boolean): Option[BrokerDTO]

  def putBroker(config:BrokerDTO): Boolean

  def removeBroker(id:String, rev:Int): Boolean

  def dispatchQueue:DispatchQueue

}

/**
 * <p>
 * A simple ConfigStore implementation which only support one broker configuration
 * stored in an XML file.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class FileConfigStore extends ConfigStore with BaseService with Logging {
  override protected def log: Log = FileConfigStore

  object StoredBrokerModel {
    def apply(config:BrokerDTO) = {
      val data = marshall(config)
      new StoredBrokerModel(config.id, config.rev, data, Hasher.JENKINS.hash(data, data.length))
    }
  }
  case class StoredBrokerModel(id:String, rev:Int, data:Array[Byte], hash:Int)

  var file:File = new File("activemq.xml")
  @volatile
  var latest:StoredBrokerModel = null
  var readOnly = false

  val dispatchQueue = createQueue("config store")

  // can't do blocking IO work on the dispatchQueue :(
  // so... use an executor
  var ioWorker:ExecutorService = null

  protected def _start(onCompleted:Runnable) = {
    ioWorker = Executors.newSingleThreadExecutor
    ioWorker.execute(^{
      startup(onCompleted)
    })
  }

  protected def _stop(onCompleted:Runnable) = {
    ioWorker.submit(^{
      onCompleted.run
      ioWorker.shutdown
    })
  }

  def startup(onCompleted:Runnable) = {

      file = file.getCanonicalFile;
      file.getParentFile.mkdir
      // Find the latest rev
      val files = file.getParentFile.listFiles
      val regex = (Pattern.quote(file.getName+".")+"""(\d)$""").r
      var revs:List[Int] = Nil
      if( files!=null ) {
        for( file <- files) {
          file.getName match {
            case regex(ver)=>
              revs ::= Integer.parseInt(ver)
            case _ =>
          }
        }
      }
      revs = revs.sortWith((x,y)=> x < y)

      val last = revs.lastOption.map{ rev=>
        read(rev, fileRev(rev))
      } match {
        case None =>
          if( !file.exists ) {
            if( readOnly ) {
              throw new Exception("file does not exsit: "+file)
            } else {
              write(StoredBrokerModel(defaultConfig(1)))
            }
          } else {
            if( readOnly ) {
              read(1, file)
            } else {
              write(read(1, file))
            }
          }
        case Some(x)=> x
      }

      dispatchQueue {
        latest = last
        schedualNextUpdateCheck
        onCompleted.run
      }
  }

                  
  def listBrokers = {
    List(latest.id)
  }


  def getBroker(id:String, eval:Boolean) = {
    if( latest.id == id ) {
      Some(unmarshall(latest.data, eval))
    } else {
      None
    }
  }

  def putBroker(config:BrokerDTO) = {
    debug("storing broker model: %s ver %d", config.id, config.rev)
    if( latest.id != config.id ) {
      debug("this store can only update broker: "+latest.id)
      false
    } else if( latest.rev+1 != config.rev ) {
      debug("update request does not match next revision: %d", latest.rev+1)
      false
    } else {
      latest = write(StoredBrokerModel(config))
      true
    }
  }

  def removeBroker(id:String, rev:Int) = {
    // not supported.
    false
  }

  private def fileRev(rev:Int) = new File(file.getParent, file.getName+"."+rev)

  private def schedualNextUpdateCheck:Unit = dispatchQueue.after(1, TimeUnit.SECONDS) {
    if( serviceState.isStarted ) {
      val latestHash = latest.hash+1
      val nextRev = latest.rev+1
      ioWorker {
        try {
          val config = read(nextRev, file)
          if (latestHash != config.hash) {
            // TODO: do this in the controller so that it
            // has a chance to update the runtime too.
            val c = unmarshall(config.data)
            c.rev = config.rev
            dispatchQueue {
              putBroker(c)
            }
          }
          schedualNextUpdateCheck
        }
        catch {
          case e:Exception =>
          // error reading the file..  could be that someone is
          // in the middle of updating the file.
        }
      }
    }
  }



  private def defaultConfig(rev:Int) = {
    val config = Broker.defaultConfig
    config.rev = rev
    config
  }

  private def read(rev:Int, file: File): StoredBrokerModel = {
    val data = IOHelper.readBytes(file)
    val config = unmarshall(data) // validates the xml
    val hash = Hasher.JENKINS.hash(data, data.length)
    StoredBrokerModel(config.id, rev, data, hash)
  }

  private  def write(config:StoredBrokerModel) = {
    // write to the files..
    IOHelper.writeBinaryFile(file, config.data)
    IOHelper.writeBinaryFile(fileRev(config.rev), config.data)
    config
  }

  def unmarshall(in:Array[Byte], evalProps:Boolean=false) = {
    if (evalProps) {
      unmarshalBrokerDTO(new ByteArrayInputStream(in), System.getProperties)
    } else {
      unmarshalBrokerDTO(new ByteArrayInputStream(in))
    }
  }

  def marshall(in:BrokerDTO) = {
    val baos = new ByteArrayOutputStream
    marshalBrokerDTO(in, baos, true)
    baos.toByteArray
  }
}

object FileConfigStore extends Log