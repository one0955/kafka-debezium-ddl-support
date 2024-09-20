package io.debezium.connector.jdbc;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.hibernate.StatelessSession;
import org.hibernate.Transaction;
import org.hibernate.jdbc.ReturningWork;
import org.hibernate.query.NativeQuery;

public class TaskStateInDBManager {
    private StatelessSession session;
    private String DdlVersionTable = "_DdlVersion_";
    private HashMap<String, String> TaskStatusTable = new HashMap<>();
    private List<String> connectedTableList = new ArrayList<>();
    private String connectedDatabase;
    private int dmlPartitionNum;

    TaskStateInDBManager(StatelessSession session, String table) {
        this.session = session;
        this.connectedDatabase = getDBname();

        String[] tablelist = table.split(",");
        for (String t : tablelist) {
            t = t.trim();
            connectedTableList.add(t);
            TaskStatusTable.put(this.connectedDatabase + "." + t, "_TaskStatus_" + t);
        }
        InitializeTable();
    }

    TaskStateInDBManager(StatelessSession session, String table, String dmlPartitionNum) {
        this(session, table);
        this.dmlPartitionNum = Integer.parseInt(dmlPartitionNum);
    }

    void InitializeTable() {
        Transaction transaction = session.beginTransaction();

        String createQuery = "CREATE TABLE IF NOT EXISTS " + DdlVersionTable + "(tablename VARCHAR(30) PRIMARY KEY, ddlversion VARCHAR(100))";
        NativeQuery createTableQuery = session.createNativeQuery(createQuery);
        createTableQuery.executeUpdate();

        for (String table : TaskStatusTable.values()) {
            createQuery = "CREATE TABLE IF NOT EXISTS " + table + "(taskid INTEGER PRIMARY KEY AUTO_INCREMENT , status INTEGER)";
            createTableQuery = session.createNativeQuery(createQuery);
            createTableQuery.executeUpdate();

        }

        transaction.commit();
    }

    void resetTaskStatusTable() {
        Transaction transaction = session.beginTransaction();
        for (String table : TaskStatusTable.values()) {
            String createQuery = "DELETE FROM " + table;
            NativeQuery createTableQuery = session.createNativeQuery(createQuery);
            createTableQuery.executeUpdate();

        }
        transaction.commit();
    }

    List<String> getTableList() {
        return this.connectedTableList;
    }

    String getDBname() {
        String DBname = session.doReturningWork(new ReturningWork<String>() {
            @Override
            public String execute(Connection connection) throws SQLException {
                String dbname = connection.getCatalog(); // 데이터베이스 이름별
                return dbname;
            }
        });
        System.out.println("\n Connection DBname : " + DBname + "/n/n");
        return DBname;
    }

    String getDdlVersion(String tablename) {
        NativeQuery query = session.createNativeQuery("SELECT * FROM " + DdlVersionTable + " WHERE tablename = :tablename");
        query.setParameter("tablename", tablename);

        Object[] row = (Object[]) query.uniqueResult();
        if (row == null)
            return null;

        List r = Arrays.asList(row);

        if (r.get(1) == null)
            return null;

        return (String) r.get(1);
    }

    HashMap<String, String> getAllDdlVersion() {
        HashMap<String, String> currentDdlVersions = new HashMap<>();

        NativeQuery query = session.createNativeQuery("SELECT * FROM " + DdlVersionTable);
        List<Object[]> rows = query.list();

        for (Object[] row : rows) {
            List r = Arrays.asList(row);
            currentDdlVersions.put((String) r.get(0), (String) r.get(1));
        }

        return currentDdlVersions;
    }

    void setDdlVersion(String tablename, String newddlverison) {
        // 트랜잭션 생략. 상위함수에서 transaction 수행
        // Transaction transaction = session.beginTransaction();

        // UPSERT Query
        String upsertQuery = "INSERT INTO " + DdlVersionTable + "(tablename, ddlversion) "
                + "VALUES (:tablename, :ddlversion) "
                + "ON DUPLICATE KEY UPDATE "
                + "ddlversion = :ddlversion";
        NativeQuery query = session.createNativeQuery(upsertQuery);
        query.setParameter("tablename", tablename);
        query.setParameter("ddlversion", newddlverison);
        query.executeUpdate();

        // transaction.commit();
    }

    boolean DdlAvailable(String tablename) {
        NativeQuery query = session.createNativeQuery("SELECT * FROM " + TaskStatusTable.get(tablename));
        List<Object[]> rows = query.list();

        int n;
        int count = 0;

        for (Object[] row : rows) {
            n = (int) row[row.length - 1];
            if (n > 0)
                count += 1;

        }

        if (count >= this.dmlPartitionNum)
            return true;
        else
            return false;
    }

    HashMap<String, Integer> getTaskID() {
        HashMap<String, Integer> TaskIDs = new HashMap<>();
        Transaction transaction = session.beginTransaction();

        for (String key : TaskStatusTable.keySet()) {
            String tablename = TaskStatusTable.get(key);
            Integer taskid = session.doReturningWork(new ReturningWork<Integer>() {
                @Override
                public Integer execute(Connection conn) throws SQLException {
                    try (PreparedStatement statement = conn.prepareStatement(
                            "INSERT INTO " + tablename + "(status) VALUES (-1)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        int rownum = statement.executeUpdate();
                        if (rownum == 0) {
                            throw new SQLException("Creating user failed, no rows affected.");
                        }
                        try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                            if (generatedKeys.next()) {
                                return generatedKeys.getInt(1);

                            }
                            else {
                                throw new SQLException("Creating user failed, no ID obtained.");
                            }
                        }

                    }
                }
            });

            System.out.println(key + "_taskid: " + taskid);
            TaskIDs.put(key, taskid);
        }

        transaction.commit();

        return TaskIDs;
    }

    int getTaskStatus(String tablename, int taskid) {
        NativeQuery query = session.createNativeQuery("SELECT * FROM " + TaskStatusTable.get(tablename) + " WHERE taskid = :taskid");
        query.setParameter("taskid", taskid);

        Object[] row = (Object[]) query.uniqueResult();
        if (row == null)
            return -1;

        List r = Arrays.asList(row);
        if (r.get(1) == null)
            return -1;
        return (Integer) r.get(1);

    }

    void setTaskStatus(String tablename, int taskid, int status) {
        Transaction transaction = session.beginTransaction();
        // UPSERT Query
        String upsertQuery = "INSERT INTO " + TaskStatusTable.get(tablename) + "(taskid, status) "
                + "VALUES (:taskid, :status) "
                + "ON DUPLICATE KEY UPDATE "
                + "status = :status";
        NativeQuery query = session.createNativeQuery(upsertQuery);
        query.setParameter("taskid", taskid);
        query.setParameter("status", status);
        query.executeUpdate();
        // commit the transaction
        transaction.commit();
    }

    void setAllTaskStatus(String tablename) {
        // UPDATE Query
        String upsertQuery = "UPDATE " + TaskStatusTable.get(tablename) + " SET status = 0";
        NativeQuery query = session.createNativeQuery(upsertQuery);
        query.executeUpdate();

    }

}
