/*
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
package org.apache.ignite.configuration.storage;

import java.io.Serializable;
import java.util.Map;

/**
 * Represents data in configuration storage.
 */
public class Data {
    /** Values. */
    private final Map<String, Serializable> values;

    /** Configuration storage version. */
    private final long cfgVersion;

    /** */
    private final long storageRevision;

    /**
     * Constructor.
     * @param values Values.
     * @param cfgVersion Version.
     * @param storageRevision Storage revision.
     */
    public Data(Map<String, Serializable> values, long cfgVersion, long storageRevision) {
        this.values = values;
        this.cfgVersion = cfgVersion;
        this.storageRevision = storageRevision;
    }

    /**
     * Get values.
     * @return Values.
     */
    public Map<String, Serializable> values() {
        return values;
    }

    /**
     * Get version.
     * @return version.
     */
    public long cfgVersion() {
        return cfgVersion;
    }

    /** */
    public long storageRevision() {
        return storageRevision;
    }
}
