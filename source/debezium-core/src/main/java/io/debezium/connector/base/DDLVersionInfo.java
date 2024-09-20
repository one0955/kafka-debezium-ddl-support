package io.debezium.connector.base;

import java.io.*;
import java.util.HashMap;

import com.google.gson.Gson;

public class DDLVersionInfo {
    private static DDLVersionInfo instance = null;

    //multitable DDLVersion 관리
    private HashMap<String, String> ddlVersionMap;

    public DDLVersionInfo() {
        // 만약에 존재하지 않으면, 새로운 ddlVersionMap = new HashMap<>(); 실행
        ddlVersionMap = new HashMap<>();
        File ddlVersionLog = new File("/home/deploy/ddlVersion.json");
        if (ddlVersionLog.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(ddlVersionLog))) {
                ddlVersionMap = new Gson().fromJson(br, HashMap.class);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static DDLVersionInfo getInstance() {
        if (instance == null) {
            instance = new DDLVersionInfo();
        }
        return instance;
    }

    // ddlVersion=db.table/queryid:gtid
    public void updateDDLVersion(String ddlVersion) {
        ddlVersionMap.put(ddlVersion.split("/")[0], ddlVersion);
    }

    // key: db.table
    public String findMatchingDDLVersion(String key) {
        String ddlVersion = ddlVersionMap.get(key);
        if (ddlVersion == null) {
            return null;
        }
        return ddlVersion;
    }

    public void storeDDLVersionMap() {
        // hashmap을 json 형태의 파일로 저장
        try {
            Writer writer = new FileWriter("/home/deploy/ddl_version.json");
            new Gson().toJson(ddlVersionMap, writer);
            writer.close();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
