/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.jdbc;

import static io.debezium.connector.jdbc.JdbcSinkConnectorConfig.SchemaEvolutionMode.NONE;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.hibernate.StatelessSession;
import org.hibernate.Transaction;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.query.NativeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.jdbc.SinkRecordDescriptor.FieldDescriptor;
import io.debezium.connector.jdbc.dialect.DatabaseDialect;
import io.debezium.connector.jdbc.naming.TableNamingStrategy;
import io.debezium.connector.jdbc.relational.TableDescriptor;
import io.debezium.connector.jdbc.relational.TableId;
import io.debezium.pipeline.sink.spi.ChangeEventSink;
import io.debezium.util.Stopwatch;
import io.debezium.util.Strings;

/**
 * A {@link ChangeEventSink} for a JDBC relational database.
 *
 * @author Chris Cranford
 */
public class JdbcChangeEventSink implements ChangeEventSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcChangeEventSink.class);

    public static final String SCHEMA_CHANGE_VALUE = "SchemaChangeValue";
    public static final String DETECT_SCHEMA_CHANGE_RECORD_MSG = "Schema change records are not supported by JDBC connector. Adjust `topics` or `topics.regex` to exclude schema change topic.";
    private final JdbcSinkConnectorConfig config;
    private final DatabaseDialect dialect;
    private final StatelessSession session;
    private final TableNamingStrategy tableNamingStrategy;
    private final RecordWriter recordWriter;

    // 아래는 새로 추가한 변수들
    /*
     * DDL_VERSION : 현재 DDL 버전 (static 변수이기 때문에 모든 클래스에서 공유 가능)
     * WaitList : DML 파티션들의 task의 현재 상태 (-1: 초기 생성 상태, 0: 작업중, 1: 비어있음, 2: 대기중)
     * maxPartitionNum : DML 파티션들의 개수(DML 파티션을 맡은 task 개수), 파티션 정보를 이 클래스에선 불러 오는 게 불가능하여 직접 입력해야 함 ㅠ
     * currentPartitionNum : 현재 task가 맞고 있는 Partition의 번호. 초기값은 -2. 이 task가 DDL 담당이면 -1로 구분
     * emptyRecordsCount : 비어있는 SinkRecords가 연속 3번 들어오면 이 partition은 비어있는 걸로 판단
     */
    private HashMap<String, Integer> currentTaskID;
    private List<String> connectedTableList;
    private String connectedDatabase;
    private HashMap<String, String> currentDdlVersions = null;
    private final TaskStateInDBManager taskStateInDBManager;

    private int emptyRecordsCount = 0;
    private int currentStatus = -1;

    public JdbcChangeEventSink(JdbcSinkConnectorConfig config, StatelessSession session, DatabaseDialect dialect, RecordWriter recordWriter,
                               TaskStateInDBManager taskStateInDBManager) {

        this.config = config;
        this.tableNamingStrategy = config.getTableNamingStrategy();
        this.dialect = dialect;
        this.session = session;
        this.recordWriter = recordWriter;
        final DatabaseVersion version = this.dialect.getVersion();

        this.taskStateInDBManager = taskStateInDBManager;

        this.currentDdlVersions = taskStateInDBManager.getAllDdlVersion();
        this.connectedDatabase = taskStateInDBManager.getDBname();
        this.connectedTableList = taskStateInDBManager.getTableList();
        this.currentTaskID = taskStateInDBManager.getTaskID();

        System.out.println(
                "DML Tasks : \n\n" +
                        "\ncurrentDdlVersions: " + this.currentDdlVersions
                        + "\nconnectedDatabase: " + this.connectedDatabase
                        + "\nconnectedTableList: " + this.connectedTableList
                        + "\nTasknum: " + this.currentTaskID
                        + "\n\n");
        LOGGER.info("Database version {}.{}.{}", version.getMajor(), version.getMinor(), version.getMicro());
    }

    public void printInfo(SinkRecord re, String value, String[] recordVersion) {
        System.out.println("Type: " + value + "\n\n" + "Record " + re + "\n\n"
                + "CURRENT_DDL_VERSION: /" + currentDdlVersions + "/\n\n" + "CurRecordVersion: /" + recordVersion[0] + "/" + recordVersion[1] + "/\n\n"
                + "currentTaskId: " + currentTaskID
                + "\n\n");

    }

    public String[] parseDdlVersion(String ddlversion) {
        String s = "/";
        String[] arr = ddlversion.split(s);

        String tablename, version;
        tablename = arr[0];
        if (arr.length == 1)
            version = "null";
        else
            version = arr[1];

        return new String[]{ tablename, version };
    }

    public boolean isSmallerVersion(String newVersion, String curVersion) {
        if (newVersion == null || curVersion == null)
            return false;

        String[] newGtid = newVersion.split(":");
        String[] curGtid = curVersion.split(":");

        if (!newGtid[1].equals(curGtid[1])) {
            return false;
        }
        else {
            if (newGtid.length <= 2 || curGtid.length <= 2)
                return false;
            Integer newId = Integer.parseInt(newGtid[2]);
            Integer curId = Integer.parseInt(curGtid[2]);

            if (newId < curId) {
                System.out.println("\n\nThis version is smaller than cur Version!! \n");
                return true;
            }
            else {
                return false;
            }

        }
    }

    @Override
    public void execute(Collection<SinkRecord> records) {

        final Map<TableId, RecordBuffer> updateBufferByTable = new HashMap<>();
        final Map<TableId, RecordBuffer> deleteBufferByTable = new HashMap<>();


        // DML SinkRecord가 비어있을 때 처리 로직
        if (records.isEmpty()) {
            ++emptyRecordsCount;
            if (emptyRecordsCount >= 2) {

                for (String table : connectedTableList) {
                    String tablename = connectedDatabase + "." + table;
                    taskStateInDBManager.setTaskStatus(tablename, currentTaskID.get(tablename), 1);
                    System.out.println("\nDML setTaskStatus 1\n");
                }
                // 비어있음(1)으로 변경 추가
                currentStatus = 1;
                emptyRecordsCount = 0;
            }
        }
        String[] recordVersion;
        for (SinkRecord record : records) {
            LOGGER.trace("Processing {}", record);

            if (record == null)
                continue;
            Struct temp = (Struct) record.value();
            if (temp == null)
                continue;

            // 배열. [0]: "DBname.TABLEname", [1]: "version"
            recordVersion = parseDdlVersion((String) temp.get("ddlVersion"));

            if (currentStatus != 0) {
                taskStateInDBManager.setTaskStatus(recordVersion[0], currentTaskID.get(recordVersion[0]), 0);
                System.out.println("\nDML setTaskStatus 0\n");

                // 작업중(0)으로 변경 추가
                currentStatus = 0;
            }

            if (isSchemaChange(record)) {
                throw new DataException("This Connector is only available to DML message, but get " + record);
            }
            else {
                printInfo(record, "DML Message ", recordVersion);

                // 새로운 버전이 현재 버전값보다 작다? -> 이미 처리한 값이라 생략
                if (isSmallerVersion(recordVersion[1], currentDdlVersions.get(recordVersion[0])))
                    continue;

                if (!recordVersion[1].equals(currentDdlVersions.get(recordVersion[0]))) {
                    flushBuffers(updateBufferByTable);
                    flushBuffers(deleteBufferByTable);

                    do {

                        if (currentStatus != 2) {
                            System.out.println("\nDML setTaskStatus 2\n");
                            // 대기중(2)으로 변경 추가
                            currentStatus = 2;
                        }
                        taskStateInDBManager.setTaskStatus(recordVersion[0], currentTaskID.get(recordVersion[0]), 2);

                        currentDdlVersions.put(recordVersion[0], taskStateInDBManager.getDdlVersion(recordVersion[0]));
                    } while (!recordVersion[1].equals(currentDdlVersions.get(recordVersion[0])));

                    if (currentStatus != 0) {
                        taskStateInDBManager.setTaskStatus(recordVersion[0], currentTaskID.get(recordVersion[0]), 0);
                        System.out.println("\nDML setTaskStatus 0\n");

                        // 작업중(0)으로 변경 추가
                        currentStatus = 0;
                    }
                    flushBuffers(updateBufferByTable);
                    flushBuffers(deleteBufferByTable);

                }
            }
            // add finish 1

            // table name ex. test_light_customers
            Optional<TableId> optionalTableId = getTableId(record);
            if (optionalTableId.isEmpty()) {

                LOGGER.warn("Ignored to write record from topic '{}' partition '{}' offset '{}'. No resolvable table name", record.topic(), record.kafkaPartition(),
                        record.kafkaOffset());
                continue;
            }

            SinkRecordDescriptor sinkRecordDescriptor = buildRecordSinkDescriptor(record);

            String tablename = ((Struct) temp.get("source")).getString("table");
            String dbname = ((Struct) temp.get("source")).getString("db");
            final TableId tableId = new TableId(dbname, null, tablename);
            // final TableId tableId = optionalTableId.get();

            if (sinkRecordDescriptor.isTombstone()) {
                // Skip only Debezium Envelope tombstone not the one produced by ExtractNewRecordState SMT
                LOGGER.debug("Skipping tombstone record {}", sinkRecordDescriptor);
                continue;
            }

            if (sinkRecordDescriptor.isDelete()) {

                if (!config.isDeleteEnabled()) {
                    LOGGER.debug("Deletes are not enabled, skipping delete for topic '{}'", sinkRecordDescriptor.getTopicName());
                    continue;
                }

                // RecordBuffer tableIdBuffer = deleteBufferByTable.computeIfAbsent(tableId, k -> new RecordBuffer(config));
                RecordBuffer tableIdBuffer = deleteBufferByTable.computeIfAbsent(tableId, k -> new RecordBuffer(config));

                List<SinkRecordDescriptor> toFlush = tableIdBuffer.add(sinkRecordDescriptor);

                flushBuffer(tableId, toFlush);
            }
            else {

                if (deleteBufferByTable.get(tableId) != null && !deleteBufferByTable.get(tableId).isEmpty()) {
                    // When an insert arrives, delete buffer must be flushed to avoid losing an insert for the same record after its deletion.
                    // this because at the end we will always flush inserts before deletes.

                    flushBuffer(tableId, deleteBufferByTable.get(tableId).flush());
                }

                Stopwatch updateBufferStopwatch = Stopwatch.reusable();
                updateBufferStopwatch.start();
                RecordBuffer tableIdBuffer = updateBufferByTable.computeIfAbsent(tableId, k -> new RecordBuffer(config));
                List<SinkRecordDescriptor> toFlush = tableIdBuffer.add(sinkRecordDescriptor);
                updateBufferStopwatch.stop();

                LOGGER.trace("[PERF] Update buffer execution time {}", updateBufferStopwatch.durations());
                flushBuffer(tableId, toFlush);
            }

        }

        flushBuffers(updateBufferByTable);
        flushBuffers(deleteBufferByTable);
    }

    private void validate(SinkRecord record) {

        if (isSchemaChange(record)) {
            LOGGER.error(DETECT_SCHEMA_CHANGE_RECORD_MSG);
            throw new DataException(DETECT_SCHEMA_CHANGE_RECORD_MSG);
        }
    }

    private static boolean isSchemaChange(SinkRecord record) {
        return record.valueSchema() != null
                && !Strings.isNullOrEmpty(record.valueSchema().name())
                && record.valueSchema().name().contains(SCHEMA_CHANGE_VALUE);
    }

    private SinkRecordDescriptor buildRecordSinkDescriptor(SinkRecord record) {

        SinkRecordDescriptor sinkRecordDescriptor;
        try {
            sinkRecordDescriptor = SinkRecordDescriptor.builder()
                    .withPrimaryKeyMode(config.getPrimaryKeyMode())
                    .withPrimaryKeyFields(config.getPrimaryKeyFields())
                    .withFieldFilters(config.getFieldsFilter())
                    .withSinkRecord(record)
                    .withDialect(dialect)
                    .build();
        }
        catch (Exception e) {
            throw new ConnectException("Failed to process a sink record", e);
        }
        return sinkRecordDescriptor;
    }

    private void flushBuffers(Map<TableId, RecordBuffer> bufferByTable) {

        bufferByTable.forEach((tableid, recordBuffer) -> flushBuffer(tableid, recordBuffer.flush()));
    }

    private void flushBuffer(TableId tableId, List<SinkRecordDescriptor> toFlush) {

        Stopwatch flushBufferStopwatch = Stopwatch.reusable();
        Stopwatch tableChangesStopwatch = Stopwatch.reusable();
        if (!toFlush.isEmpty()) {
            LOGGER.debug("Flushing records in JDBC Writer for table: {}", tableId.getTableName());
            try {
                tableChangesStopwatch.start();
                final TableDescriptor table = readTable(tableId);
                tableChangesStopwatch.stop();
                String sqlStatement = getSqlStatement(table, toFlush.get(0));
                flushBufferStopwatch.start();
                recordWriter.write(toFlush, sqlStatement);
                flushBufferStopwatch.stop();

                LOGGER.trace("[PERF] Flush buffer execution time {}", flushBufferStopwatch.durations());
                LOGGER.trace("[PERF] Table changes execution time {}", tableChangesStopwatch.durations());
            }
            catch (Exception e) {
                throw new ConnectException("Failed to process a sink record", e);
            }
        }
    }

    private Optional<TableId> getTableId(SinkRecord record) {

        String tableName = tableNamingStrategy.resolveTableName(config, record);
        if (tableName == null) {
            return Optional.empty();
        }

        return Optional.of(dialect.getTableId(tableName));
    }

    @Override
    public void close() {
        if (session != null && session.isOpen()) {
            LOGGER.info("Closing session.");
            session.close();
        }
        else {
            LOGGER.info("Session already closed.");
        }
    }

    private TableDescriptor checkAndApplyTableChangesIfNeeded(TableId tableId, SinkRecordDescriptor descriptor) throws SQLException {
        if (!hasTable(tableId)) {
            // Table does not exist, lets attempt to create it.
            try {
                return createTable(tableId, descriptor);
            }
            catch (SQLException ce) {
                // It's possible the table may have been created in the interim, so try to alter.
                LOGGER.warn("Table creation failed for '{}', attempting to alter the table", tableId.toFullIdentiferString(), ce);
                try {
                    return alterTableIfNeeded(tableId, descriptor);
                }
                catch (SQLException ae) {
                    // The alter failed, hard stop.
                    LOGGER.error("Failed to alter the table '{}'.", tableId.toFullIdentiferString(), ae);
                    throw ae;
                }
            }
        }
        else {
            // Table exists, lets attempt to alter it if necessary.
            try {
                return alterTableIfNeeded(tableId, descriptor);
            }
            catch (SQLException ae) {
                LOGGER.error("Failed to alter the table '{}'.", tableId.toFullIdentiferString(), ae);
                throw ae;
            }
        }
    }

    private boolean hasTable(TableId tableId) {
        return session.doReturningWork((connection) -> dialect.tableExists(connection, tableId));
    }

    private TableDescriptor readTable(TableId tableId) {
        return session.doReturningWork((connection) -> dialect.readTable(connection, tableId));
    }

    private TableDescriptor createTable(TableId tableId, SinkRecordDescriptor record) throws SQLException {
        LOGGER.debug("Attempting to create table '{}'.", tableId.toFullIdentiferString());

        if (NONE.equals(config.getSchemaEvolutionMode())) {
            LOGGER.warn("Table '{}' cannot be created because schema evolution is disabled.", tableId.toFullIdentiferString());
            throw new SQLException("Cannot create table " + tableId.toFullIdentiferString() + " because schema evolution is disabled");
        }

        Transaction transaction = session.beginTransaction();
        try {
            final String createSql = dialect.getCreateTableStatement(record, tableId);
            LOGGER.trace("SQL: {}", createSql);
            session.createNativeQuery(createSql, Object.class).executeUpdate();
            transaction.commit();
        }
        catch (Exception e) {
            transaction.rollback();
            throw e;
        }

        return readTable(tableId);
    }

    private TableDescriptor alterTableIfNeeded(TableId tableId, SinkRecordDescriptor record) throws SQLException {
        LOGGER.debug("Attempting to alter table '{}'.", tableId.toFullIdentiferString());

        if (!hasTable(tableId)) {
            LOGGER.error("Table '{}' does not exist and cannot be altered.", tableId.toFullIdentiferString());
            throw new SQLException("Could not find table: " + tableId.toFullIdentiferString());
        }

        // Resolve table metadata from the database
        final TableDescriptor table = readTable(tableId);

        // Delegating to dialect to deal with database case sensitivity.
        Set<String> missingFields = dialect.resolveMissingFields(record, table);
        if (missingFields.isEmpty()) {
            // There are no missing fields, simply return
            // todo: should we check column type changes or default value changes?
            return table;
        }

        LOGGER.debug("The follow fields are missing in the table: {}", missingFields);
        for (String missingFieldName : missingFields) {
            final FieldDescriptor fieldDescriptor = record.getFields().get(missingFieldName);
            if (!fieldDescriptor.getSchema().isOptional() && fieldDescriptor.getSchema().defaultValue() == null) {
                throw new SQLException(String.format(
                        "Cannot ALTER table '%s' because field '%s' is not optional but has no default value",
                        tableId.toFullIdentiferString(), fieldDescriptor.getName()));
            }
        }

        if (NONE.equals(config.getSchemaEvolutionMode())) {
            LOGGER.warn("Table '{}' cannot be altered because schema evolution is disabled.", tableId.toFullIdentiferString());
            throw new SQLException("Cannot alter table " + tableId.toFullIdentiferString() + " because schema evolution is disabled");
        }

        Transaction transaction = session.beginTransaction();
        try {
            final String alterSql = dialect.getAlterTableStatement(table, record, missingFields);
            LOGGER.trace("SQL: {}", alterSql);
            session.createNativeQuery(alterSql, Object.class).executeUpdate();
            transaction.commit();
        }
        catch (Exception e) {
            transaction.rollback();
            throw e;
        }

        return readTable(tableId);
    }

    private String getSqlStatement(TableDescriptor table, SinkRecordDescriptor record) {

        if (!record.isDelete()) {
            switch (config.getInsertMode()) {
                case INSERT:
                    return dialect.getInsertStatement(table, record);
                case UPSERT:
                    if (record.getKeyFieldNames().isEmpty()) {
                        throw new ConnectException("Cannot write to table " + table.getId().getTableName() + " with no key fields defined.");
                    }
                    return dialect.getUpsertStatement(table, record);
                case UPDATE:
                    return dialect.getUpdateStatement(table, record);
            }
        }
        else {
            return dialect.getDeleteStatement(table, record);
        }

        throw new DataException(String.format("Unable to get SQL statement for %s", record));
    }

    private void writeTruncate(String sql) throws SQLException {

        final Transaction transaction = session.beginTransaction();
        try {
            LOGGER.trace("SQL: {}", sql);
            final NativeQuery<?> query = session.createNativeQuery(sql, Object.class);

            query.executeUpdate();
            transaction.commit();
        }
        catch (Exception e) {
            transaction.rollback();
            throw e;
        }
    }
}
