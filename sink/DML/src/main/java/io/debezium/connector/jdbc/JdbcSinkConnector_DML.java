/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.sink.SinkConnector;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;

import io.debezium.annotation.Immutable;

/**
 * The main connector class used to instantiate configuration and execution classes.
 *
 * @author Hossein Torabi
 */
public class JdbcSinkConnector_DML extends SinkConnector {

    @Immutable
    private Map<String, String> properties;

    @Override
    public String version() {
        return Module.version();
    }

    @Override
    public void start(Map<String, String> props) {

        JdbcSinkConnectorConfig config = new JdbcSinkConnectorConfig(props);
        config.validate();

        SessionFactory sessionFactory = null;
        StatelessSession session = null;
        try {
            sessionFactory = config.getHibernateConfiguration().buildSessionFactory();
            session = sessionFactory.openStatelessSession();

            TaskStateInDBManager taskStateInDBManager = new TaskStateInDBManager(session, props.get("dml.tablelist"));
            taskStateInDBManager.resetTaskStatusTable();

        }
        catch (Exception e) {
            throw e;
        }
        finally {

            if (session != null) {
                session.close();
            }
            if (sessionFactory != null) {
                sessionFactory.close();
            }
        }
        config = null;

        this.properties = Map.copyOf(props);
    }

    @Override
    public Class<? extends Task> taskClass() {
        return JdbcSinkConnectorTask.class;
    }

    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        final List<Map<String, String>> configs = new ArrayList<>(maxTasks);
        for (int i = 0; i < maxTasks; ++i) {
            configs.add(properties);
        }
        return configs;
    }

    @Override
    public void stop() {
    }

    @Override
    public ConfigDef config() {
        return JdbcSinkConnectorConfig.configDef();
    }

}
