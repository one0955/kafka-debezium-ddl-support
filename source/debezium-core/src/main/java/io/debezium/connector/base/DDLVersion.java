package io.debezium.connector.base;

import java.io.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DDLVersion {
    private static final Logger logger = LoggerFactory.getLogger(DDLVersion.class);
    private String previousVersion = null;

    private DDLVersion() {
        File ddlVersionLog = new File("./kafka/logs/ddl_version.txt");
        if (ddlVersionLog.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(ddlVersionLog))) {
                previousVersion = reader.readLine();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static class LazyHolder {
        private static DDLVersion instance = new DDLVersion();
    }

    public static DDLVersion getInstance() {
        return LazyHolder.instance;
    }

    public synchronized void updateVersion(String newVersion) {
        if (newVersion != null && !newVersion.equals(previousVersion)) {
            previousVersion = newVersion;
            logger.info(newVersion);
        }
    }

    public synchronized void storeVersion() {
        File ddlVersionLog = new File("./kafka/logs/ddl_version.txt");

        try (FileWriter writer = new FileWriter(ddlVersionLog, false)) {
            writer.write(previousVersion + "\n");
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getCurrentVersion() {
        return this.previousVersion;
    }

}
