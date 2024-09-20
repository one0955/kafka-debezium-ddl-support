package io.debezium.connector.jdbc.HibernateORM;

import jakarta.persistence.*;

@Entity
@Table(name = "TasksStatus")
public class TasksStatus {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Column(name = "taskid")
    private String taskid;
    @Column(name = "taskstatus")
    private String taskstatus;
}
