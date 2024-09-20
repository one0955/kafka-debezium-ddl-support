/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.jdbc;

import static io.debezium.connector.jdbc.JdbcSinkConnectorConfig.SchemaEvolutionMode.NONE;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.debezium.connector.jdbc.SinkRecordDescriptor.FieldDescriptor;
import io.debezium.connector.jdbc.dialect.DatabaseDialect;
import io.debezium.connector.jdbc.naming.TableNamingStrategy;
import io.debezium.connector.jdbc.relational.TableDescriptor;
import io.debezium.connector.jdbc.relational.TableId;
import io.debezium.pipeline.sink.spi.ChangeEventSink;
import io.debezium.util.Stopwatch;
import io.debezium.util.Strings;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;

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
     * connectedTableList : 반영하고자 하는 테이블 리스트
     * currentDdlVersions : 현재 DDL 버전 (static 변수이기 때문에 모든 클래스에서 공유 가능)
     * taskStateInDBManager : TargetDB에 버전 및 상태를 모두 관리하는 클래스
     * KAFKA_CONNECTOR_STATUS_URI : DML Connector 상태 정보 링크
     */
    private HashMap<String, Integer> currentTaskID;
    private List<String> connectedTableList;
    private String connectedDatabase;
    private HashMap<String, String> currentDdlVersions = null;
    private final TaskStateInDBManager taskStateInDBManager;

    private static String KAFKA_CONNECTOR_STATUS_URI;
    private static HttpClient httpClient = HttpClient.newHttpClient();
    private static HttpRequest request;

    public JdbcChangeEventSink(JdbcSinkConnectorConfig config, StatelessSession session, DatabaseDialect dialect, RecordWriter recordWriter,
                               TaskStateInDBManager taskStateInDBManager, String dmlConnectorUri) {

        this.config = config;
        this.tableNamingStrategy = config.getTableNamingStrategy();
        this.dialect = dialect;
        this.session = session;
        this.recordWriter = recordWriter;
        final DatabaseVersion version = this.dialect.getVersion();

        KAFKA_CONNECTOR_STATUS_URI = dmlConnectorUri;
        try {
            this.setHttpRequest();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        this.taskStateInDBManager = taskStateInDBManager;

        this.currentDdlVersions = taskStateInDBManager.getAllDdlVersion();
        this.connectedDatabase = taskStateInDBManager.getDBname();
        this.connectedTableList = taskStateInDBManager.getTableList();

        System.out.println(
                "\nDML_CONNCTOR_NAME" + dmlConnectorUri
                        + "\ncurrentDdlVersions: " + this.currentDdlVersions
                        + "\nconnectedDatabase: " + this.connectedDatabase
                        + "\nconnectedTableList: " + this.connectedTableList
                        + "\n\n");
        LOGGER.info("Database version {}.{}.{}", version.getMajor(), version.getMinor(), version.getMicro());
    }

    public void setHttpRequest() throws Exception {
        URI url = new URI(KAFKA_CONNECTOR_STATUS_URI);
        request = HttpRequest.newBuilder().uri(url).GET().build();
    }

    //DML Connector가 살아있는지 확인
    public boolean checkDmlTaskisAlive() {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            // JSON 문자열을 JsonNode로 파싱
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonBody = objectMapper.readTree(responseBody);

            if (jsonBody.has("error_code"))
                return false;

            JsonNode tasks = jsonBody.get("tasks");

            for (JsonNode task : tasks) {
                if (!"RUNNING".equals(task.get("state").asText()))
                    return false;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        return true;
    }

    private static String replaceIgnoreCase(String source, String target, String replacement) {
        Pattern pattern = Pattern.compile(target, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(source);
        return matcher.replaceAll(replacement);
    }

    private void writeDDLQuery(SinkRecord sinkrecord, String tablename, String newversion) {
        final Struct value = (Struct) sinkrecord.value();
        String sqlStatement = value.getString("ddl");
        String dbName = value.getString("databaseName");

        String[] queryIncludeList = { "CREATE TABLE", "ALTER TABLE", "CREATE INDEX", "DROP TABLE", "RENAME TABLE" };

        Boolean isAvailable = false;
        for (String word : queryIncludeList) {
            if (sqlStatement.toUpperCase().contains(word)) {
                isAvailable = true;
                break;
            }
        }

        LOGGER.debug("Flushing schemachange records in JDBC Writer for table: {DDL}");
        try {
            if (isAvailable) {
                if (sqlStatement.toUpperCase().contains("CREATE TABLE")) {
                    recordWriter.writeschema(sqlStatement, dbName, tablename, newversion);
                }
                else if (sqlStatement.toUpperCase().contains(" MODIFY ")) {
                    Alter alterStatement = (Alter) CCJSqlParserUtil.parse(sqlStatement);
                    AlterExpression alterExpression = alterStatement.getAlterExpressions().get(0);
                    String temp = alterExpression.getColDataTypeList().get(0).toString();
                    String[] columnInfo = temp.split(" ", 2);
                    System.out.println("MODIFY columInfo: " + columnInfo[0] + " , " + columnInfo[1]);

                    SingleStoreMODIFY(columnInfo, tablename, newversion);

                }
                else if (sqlStatement.toUpperCase().contains("RENAME TABLE")) {
                    sqlStatement = replaceIgnoreCase(sqlStatement, "RENAME TABLE ", "ALTER TABLE ");
                    sqlStatement = replaceIgnoreCase(sqlStatement, " TO ", " RENAME TO ");
                    recordWriter.writeschema(sqlStatement, dbName, tablename, newversion);
                }
                else {
                    if (sqlStatement.toUpperCase().contains("RENAME COLUMN")) {
                        sqlStatement = replaceIgnoreCase(sqlStatement, "RENAME COLUMN", "CHANGE");
                        sqlStatement = replaceIgnoreCase(sqlStatement, " TO ", " ");
                    }
                    if (newversion.endsWith("null"))
                        newversion = "1" + newversion;
                    recordWriter.writeschema(sqlStatement, dbName, tablename, newversion);
                }
            }
            else {
                final Transaction transaction = session.beginTransaction();
                this.taskStateInDBManager.setDdlVersion(tablename, newversion);
                this.taskStateInDBManager.setAllTaskStatus(tablename);
                transaction.commit();
            }

        }
        catch (Exception e) {
            throw new ConnectException("Failed to process a schemachange sink record-: " + sqlStatement, e);
        }

    }

    void SingleStoreMODIFY(String[] columnInfo, String tablename, String newversion) {
        // 트랜잭션 생략. 상위함수에서 transaction 수행
        Transaction transaction = session.beginTransaction();

        // String[] cast = columnInfo[1].split(" ");
        // cast[0] = cast[0].toUpperCase().replace("VAR", "");
        List<String> queryList = new ArrayList<>();
        queryList.add(String.format("ALTER TABLE %s ADD %s %s;", tablename, columnInfo[0] + "_new", columnInfo[1]));
        queryList.add(String.format("UPDATE %s SET %s = %s;", tablename, columnInfo[0] + "_new", columnInfo[0]));
        queryList.add(String.format("ALTER TABLE %s DROP %s;", tablename, columnInfo[0]));
        queryList.add(String.format("ALTER TABLE %s CHANGE %s %s;", tablename, columnInfo[0] + "_new", columnInfo[0]));

        // UPSERT Query
        for (String q : queryList) {
            System.out.println("MODIFY query: " + q);
            NativeQuery query = session.createNativeQuery(q);
            query.executeUpdate();
        }

        this.taskStateInDBManager.setDdlVersion(tablename, newversion);
        this.taskStateInDBManager.setAllTaskStatus(tablename);

        transaction.commit();
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

    public boolean checkIncludeTableList(String tablename) {

        for (String s : connectedTableList) {
            if (tablename.equals(connectedDatabase + "." + s))
                return true;
            else if (tablename.equals(connectedDatabase + "._" + s + "_new"))
                return true;
        }

        return false;
    }

    public boolean isNewVersion(String newVersion, String curVersion) {
        if (newVersion == null || curVersion == null)
            return true;

        String[] newGtid = newVersion.split(":", -1);
        String[] curGtid = curVersion.split(":", -1);

        if (!newGtid[1].equals(curGtid[1])) {
            return true;
        }
        else {
            if (newGtid.length <= 2 || curGtid.length <= 2)
                return true;
            Integer newId = Integer.parseInt(newGtid[2]);
            Integer curId = Integer.parseInt(curGtid[2]);

            if (newId > curId)
                return true;
            else {
                if (newGtid[0].equals(curGtid[0])) {
                    return false;
                }
                else {
                    return true;
                }

            }

        }
    }

    @Override
    public void execute(Collection<SinkRecord> records) {

        final Map<TableId, RecordBuffer> updateBufferByTable = new HashMap<>();
        final Map<TableId, RecordBuffer> deleteBufferByTable = new HashMap<>();

        // SinkRecord가 비어있을 때 처리 로직

        String[] recordVersion;
        for (SinkRecord record : records) {
            LOGGER.trace("Processing DDL Task: {}", record);

            // 레코드 값 자체가 null이면 생략
            if (record == null)
                continue;
            Struct temp = (Struct) record.value();
            if (temp == null)
                continue;

            // 배열. [0]: "DBname.TABLEname", [1]: "version"
            recordVersion = parseDdlVersion((String) temp.get("ddlVersion"));

            // 해당하는 테이블의 버전이 아닌 경우 생략
            if (!checkIncludeTableList(recordVersion[0]))
                continue;

            // 해당 record가 DDL 메시지인 경우
            if (isSchemaChange(record)) {
                printInfo(record, "DDL Message", recordVersion);

                // 처음 DDL 메시지는 모두 실행
                if (recordVersion[1].endsWith("null")) {
                    writeDDLQuery(record, recordVersion[0], recordVersion[1]);
                    continue;
                }
                // 이전이랑 같은 버전이면 생략
                if (!isNewVersion(recordVersion[1], currentDdlVersions.get(recordVersion[0])))
                    continue;

                // 1. dml connector가 정상동작할때까지 대기
                // 2. 모든 DML Task들이 이전 VERSION의 메시지들을 처리할때까지 대기
                while (!checkDmlTaskisAlive() || !taskStateInDBManager.DdlAvailable(recordVersion[0]))
                    continue;

                // DDL 반영
                writeDDLQuery(record, recordVersion[0], recordVersion[1]);
                currentDdlVersions.put(recordVersion[0], recordVersion[1]);

            }
            else {
                throw new DataException("This Connector is only available to DDL message, but get " + record);
            }

        }

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

        bufferByTable.forEach((tableId, recordBuffer) -> flushBuffer(tableId, recordBuffer.flush()));
    }

    private void flushBuffer(TableId tableId, List<SinkRecordDescriptor> toFlush) {

        Stopwatch flushBufferStopwatch = Stopwatch.reusable();
        Stopwatch tableChangesStopwatch = Stopwatch.reusable();
        if (!toFlush.isEmpty()) {
            LOGGER.debug("Flushing records in JDBC Writer for table: {}", tableId.getTableName());
            try {
                tableChangesStopwatch.start();
                final TableDescriptor table = checkAndApplyTableChangesIfNeeded(tableId, toFlush.get(0));
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
