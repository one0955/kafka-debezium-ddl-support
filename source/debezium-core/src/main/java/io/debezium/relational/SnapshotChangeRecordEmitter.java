/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.relational;

import io.debezium.connector.base.DDLVersionInfo;
import io.debezium.data.Envelope.Operation;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;
import io.debezium.util.Clock;

/**
 * Emits change data based on a single row read via JDBC.
 *
 * @author Jiri Pechanec
 */
public class SnapshotChangeRecordEmitter<P extends Partition> extends RelationalChangeRecordEmitter<P> {

    private final Object[] row;

    public SnapshotChangeRecordEmitter(P partition, OffsetContext offset, Object[] row, Clock clock, RelationalDatabaseConnectorConfig connectorConfig) {
        super(partition, offset, clock, connectorConfig);

        this.row = row;
    }

    @Override
    public Operation getOperation() {
        return Operation.READ;
    }

    @Override
    protected Object[] getOldColumnValues() {
        throw new UnsupportedOperationException("Can't get old row values for READ record");
    }

    @Override
    protected Object[] getNewColumnValues() {
        return row;
    }

    @Override
    protected String getDdlVersion() {
        String tableInstance = getOffset().getSourceInfo().getString("table");
        if (tableInstance.startsWith("_") && (tableInstance.endsWith("_new") || tableInstance.endsWith("_old"))) {
            tableInstance = tableInstance.replaceAll("^_(.*?)_.*$", "$1");
        }
        return DDLVersionInfo.getInstance().findMatchingDDLVersion(getOffset().getSourceInfo().getString("db") + "." + tableInstance);
    }
}
