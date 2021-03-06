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
package org.apache.activemq.apollo.openwire.codec;

import org.apache.activemq.apollo.openwire.command.DataStructure;
import org.fusesource.hawtbuf.DataByteArrayInputStream;
import org.fusesource.hawtbuf.DataByteArrayOutputStream;

import java.io.IOException;

public interface DataStreamMarshaller {

    byte getDataStructureType();
    DataStructure createObject();

    int tightMarshal1(OpenWireFormat format, Object c, BooleanStream bs) throws IOException;
    void tightMarshal2(OpenWireFormat format, Object c, DataByteArrayOutputStream ds, BooleanStream bs) throws IOException;
    void tightUnmarshal(OpenWireFormat format, Object data, DataByteArrayInputStream dis, BooleanStream bs) throws IOException;

    void looseMarshal(OpenWireFormat format, Object c, DataByteArrayOutputStream ds) throws IOException;
    void looseUnmarshal(OpenWireFormat format, Object data, DataByteArrayInputStream dis) throws IOException;
    
}
