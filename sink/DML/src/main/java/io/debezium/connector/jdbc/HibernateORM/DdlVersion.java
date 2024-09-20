package io.debezium.connector.jdbc.HibernateORM;

import jakarta.persistence.*;

@Entity
@Table(name = "DdlVersion")
public class DdlVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Column(name = "tablename")
    private String tablename;
    @Column(name = "ddlversion")
    private String ddlversion;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTablename() {
        return tablename;
    }

    public void setTablename(String tablename) {
        this.tablename = tablename;
    }

    public String getDdlversion() {
        return ddlversion;
    }

    public void setDdlversion(String ddlversion) {
        this.ddlversion = ddlversion;
    }
}
