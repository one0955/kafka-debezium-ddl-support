/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.schemagenerator.schema;

import java.io.IOException;
import java.util.Map;

import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import io.debezium.util.IoUtil;
import io.smallrye.openapi.api.constants.OpenApiConstants;
import io.smallrye.openapi.api.models.ComponentsImpl;
import io.smallrye.openapi.api.models.OpenAPIImpl;
import io.smallrye.openapi.api.models.info.InfoImpl;
import io.smallrye.openapi.runtime.io.Format;
import io.smallrye.openapi.runtime.io.OpenApiSerializer;

@SchemaName("openapi")
public class OpenApiSchema implements Schema {

    private static final SchemaDescriptor DESCRIPTOR = new SchemaDescriptor() {
        @Override
        public String getId() {
            return "openapi";
        }

        @Override
        public String getName() {
            return "OpenAPI";
        }

        @Override
        public String getVersion() {
            return "3.0.3";
        }

        @Override
        public String getDescription() {
            return "TBD";
        }
    };

    private Format format = Format.JSON;

    @Override
    public SchemaDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void configure(Map<String, Object> config) {
        if (null == config || config.isEmpty()) {
            return;
        }
        config.forEach((property, value) -> {
            switch (property) {
                case "format":
                    format = Format.valueOf((String) value);
                    break;
                default:
                    break;
            }
        });
    }

    @Override
    public String getSpec(org.eclipse.microprofile.openapi.models.media.Schema connectorSchema) {
        OpenAPI debeziumAPI = new OpenAPIImpl();
        debeziumAPI.setOpenapi(OpenApiConstants.OPEN_API_VERSION);

        Components debeziumConnectorTypeComponents = new ComponentsImpl();

        debeziumAPI.setInfo(new InfoImpl());
        debeziumAPI.getInfo().setTitle("Generated by Debezium OpenAPI Generator");

        debeziumAPI.getInfo().setVersion(
                IoUtil.loadProperties(OpenApiSchema.class, "io/debezium/schemagenerator/build.properties").getProperty("version"));

        debeziumConnectorTypeComponents.addSchema(
                "debezium-" + connectorSchema.getExtensions().get("connector-id") + "-" + connectorSchema.getExtensions().get("version"),
                connectorSchema);

        debeziumAPI.setComponents(debeziumConnectorTypeComponents);

        try {
            return OpenApiSerializer.serialize(debeziumAPI, format);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
