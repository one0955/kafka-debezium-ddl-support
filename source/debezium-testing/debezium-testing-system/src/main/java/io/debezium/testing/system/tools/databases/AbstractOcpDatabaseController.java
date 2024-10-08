/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.testing.system.tools.databases;

import java.io.OutputStream;
import java.io.PipedInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.testing.system.tools.OpenShiftUtils;
import io.debezium.testing.system.tools.WaitConditions;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.TtyExecErrorChannelable;
import io.fabric8.openshift.client.OpenShiftClient;

/**
 *
 * @author Jakub Cechacek
 */
public abstract class AbstractOcpDatabaseController<C extends DatabaseClient<?, ?>>
        implements DatabaseController<C> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractOcpDatabaseController.class);

    protected final OpenShiftClient ocp;
    protected final String project;
    protected final OpenShiftUtils ocpUtils;
    protected Deployment deployment;
    protected String name;
    protected List<Service> services;

    public AbstractOcpDatabaseController(
                                         Deployment deployment, List<Service> services, OpenShiftClient ocp) {
        this.deployment = deployment;
        this.name = deployment.getMetadata().getName();
        this.project = deployment.getMetadata().getNamespace();
        this.services = services;
        this.ocp = ocp;
        this.ocpUtils = new OpenShiftUtils(ocp);
    }

    private Service getService() {
        return ocp
                .services()
                .inNamespace(project)
                .withName(deployment.getMetadata().getName())
                .get();
    }

    @Override
    public void reload() throws InterruptedException {
        LOGGER.info("Removing all pods of '" + name + "' deployment in namespace '" + project + "'");
        ocpUtils.scaleDeploymentToZero(deployment);
        LOGGER.info("Restoring all pods of '" + name + "' deployment in namespace '" + project + "'");
        ocp.apps().deployments().inNamespace(project).withName(name).scale(1);
    }

    @Override
    public String getDatabaseHostname() {
        return getService().getMetadata().getName() + "." + project + ".svc.cluster.local";
    }

    @Override
    public int getDatabasePort() {
        return getOriginalDatabasePort();
    }

    @Override
    public String getPublicDatabaseHostname() {
        return getDatabaseHostname();
    }

    @Override
    public int getPublicDatabasePort() {
        return getDatabasePort();
    }

    @Override
    public void initialize() throws InterruptedException {
        LOGGER.info("Removed port forward");
    }

    protected void executeInitCommand(Deployment deployment, String... commands) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        String containerName = deployment.getMetadata().getLabels().get("app");
        try (var ignored = prepareExec(deployment)
                .usingListener(new DatabaseInitListener(containerName, latch))
                .exec(commands)) {
            LOGGER.info("Waiting until database is initialized");
            latch.await(WaitConditions.scaled(1), TimeUnit.MINUTES);
        }
    }

    protected void executeCommand(Deployment deployment, String... commands) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try (var ignored = prepareExec(deployment)
                .usingListener(new DatabaseExecListener(deployment.getMetadata().getName(), latch))
                .exec(commands)) {
            LOGGER.info("Waiting on " + deployment.getMetadata().getName() + " for commands " + Arrays.toString(commands));
            latch.await(WaitConditions.scaled(1), TimeUnit.MINUTES);
        }
    }

    private TtyExecErrorChannelable<String, OutputStream, PipedInputStream, ExecWatch> prepareExec(Deployment deployment) {
        var pods = ocpUtils.podsForDeployment(deployment);
        if (pods.size() > 1) {
            throw new IllegalArgumentException("Executing command on deployment scaled to more than 1");
        }
        Pod pod = pods.get(0);
        return getPodResource(pod)
                .inContainer(pod.getMetadata().getLabels().get("app"))
                .writingError(System.err); // CHECKSTYLE IGNORE RegexpSinglelineJava FOR NEXT 1 LINES
    }

    private PodResource<Pod> getPodResource(Pod pod) {
        return ocp.pods().inNamespace(project).withName(pod.getMetadata().getName());
    }

    private int getOriginalDatabasePort() {
        return getService().getSpec().getPorts().stream()
                .filter(p -> p.getName().equals("db"))
                .findAny()
                .get().getPort();
    }
}
