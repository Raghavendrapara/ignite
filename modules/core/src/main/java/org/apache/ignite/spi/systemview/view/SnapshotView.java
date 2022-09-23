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

package org.apache.ignite.spi.systemview.view;

import java.util.Collection;
import org.apache.ignite.internal.managers.systemview.walker.Order;
import org.apache.ignite.internal.pagemem.wal.record.delta.ClusterSnapshotRecord;
import org.apache.ignite.internal.processors.cache.persistence.snapshot.SnapshotMetadata;
import org.apache.ignite.internal.util.typedef.F;

/**
 * Snapshot representation for a {@link SystemView}.
 */
public class SnapshotView {
    /** Snapshot system view name. */
    public static final String SNAPSHOT_SYS_VIEW = "snapshot";

    /** Snapshot system view description. */
    public static final String SNAPSHOT_SYS_VIEW_DESC = "Snapshot";

    /** Snapshot name. */
    private final String name;

    /** Node consistent ID. */
    private final String consistentId;

    /** Baseline nodes affected by the snapshot. */
    private final String baselineNodes;

    /** Cache group names that were included in the snapshot. */
    private final String cacheGrps;

    /** WAL segment that contains {@link ClusterSnapshotRecord} if exists. */
    private final Long snpRecSeg;

    /**
     * @param meta Snapshot metadata.
     * @param cacheGrps Cache group names that were included in the snapshot.
     */
    public SnapshotView(
        SnapshotMetadata meta,
        Collection<String> cacheGrps
    ) {
        name = meta.snapshotName();
        consistentId = meta.consistentId();
        baselineNodes = F.concat(meta.baselineNodes(), ",");
        snpRecSeg = meta.snapshotRecordPointer() == null ? null : meta.snapshotRecordPointer().index();

        this.cacheGrps = F.concat(cacheGrps, ",");
    }

    /**
     * @return Snapshot name.
     */
    @Order
    public String name() {
        return name;
    }

    /**
     * @return Node consistent ID.
     */
    @Order(1)
    public String consistentId() {
        return consistentId;
    }

    /**
     * @return Baseline nodes affected by the snapshot.
     */
    @Order(2)
    public String baselineNodes() {
        return baselineNodes;
    }

    /**
     * @return Cache group names that were included in the snapshot.
     */
    @Order(3)
    public String cacheGroups() {
        return cacheGrps;
    }

    /**
     * @return WAL segment that contains {@link ClusterSnapshotRecord} if exists.
     */
    @Order(4)
    public Long snapshotRecordSegment() {
        return snpRecSeg;
    }
}
