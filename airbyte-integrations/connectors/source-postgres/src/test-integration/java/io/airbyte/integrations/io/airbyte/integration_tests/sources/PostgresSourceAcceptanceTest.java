/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.io.airbyte.integration_tests.sources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.airbyte.commons.features.EnvVariableFeatureFlags;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.Database;
import io.airbyte.db.factory.DSLContextFactory;
import io.airbyte.db.factory.DatabaseDriver;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.base.ssh.SshHelpers;
import io.airbyte.integrations.standardtest.source.SourceAcceptanceTest;
import io.airbyte.integrations.standardtest.source.TestDestinationEnv;
import io.airbyte.integrations.util.HostPortResolver;
import io.airbyte.protocol.models.AirbyteCatalog;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import io.airbyte.protocol.models.ConnectorSpecification;
import io.airbyte.protocol.models.DestinationSyncMode;
import io.airbyte.protocol.models.Field;
import io.airbyte.protocol.models.JsonSchemaType;
import io.airbyte.protocol.models.SyncMode;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith(SystemStubsExtension.class)
public class PostgresSourceAcceptanceTest extends SourceAcceptanceTest {

  @SystemStub
  private EnvironmentVariables environmentVariables;

  private static final String STREAM_NAME = "public.id_and_name";
  private static final String STREAM_NAME2 = "public.starships";
  private static final String STREAM_NAME_MATERIALIZED_VIEW = "public.testview";
  public static final String LIMIT_PERMISSION_SCHEMA = "limit_perm_schema";
  public static final String LIMIT_PERMISSION_ROLE = "limit_perm_role";
  public static final String LIMIT_PERMISSION_ROLE_PASSWORD = "test";

  private PostgreSQLContainer<?> container;
  private JsonNode config;
  private Database database;
  private ConfiguredAirbyteCatalog configCatalog;

  @Override
  protected void setupEnvironment(final TestDestinationEnv environment) throws Exception {
    environmentVariables.set(EnvVariableFeatureFlags.USE_STREAM_CAPABLE_STATE, "true");

    container = new PostgreSQLContainer<>("postgres:13-alpine");
    container.start();
    String username = container.getUsername();
    String password = container.getPassword();
    List<String> schemas = List.of("public");
    config = getConfig(username, password, schemas);
    try (final DSLContext dslContext = DSLContextFactory.create(
        config.get(JdbcUtils.USERNAME_KEY).asText(),
        config.get(JdbcUtils.PASSWORD_KEY).asText(),
        DatabaseDriver.POSTGRESQL.getDriverClassName(),
        String.format(DatabaseDriver.POSTGRESQL.getUrlFormatString(),
            container.getHost(),
            container.getFirstMappedPort(),
            config.get(JdbcUtils.DATABASE_KEY).asText()),
        SQLDialect.POSTGRES)) {
      database = new Database(dslContext);

      database.query(ctx -> {
        ctx.fetch("CREATE TABLE id_and_name(id INTEGER, name VARCHAR(200));");
        ctx.fetch("INSERT INTO id_and_name (id, name) VALUES (1,'picard'),  (2, 'crusher'), (3, 'vash');");
        ctx.fetch("CREATE TABLE starships(id INTEGER, name VARCHAR(200));");
        ctx.fetch("INSERT INTO starships (id, name) VALUES (1,'enterprise-d'),  (2, 'defiant'), (3, 'yamato');");
        ctx.fetch("CREATE MATERIALIZED VIEW testview AS select * from id_and_name where id = '2';");
        return null;
      });
      configCatalog = getCommonConfigCatalog();
    }
  }

  private JsonNode getConfig(String username, String password, List<String> schemas) {
    final JsonNode replicationMethod = Jsons.jsonNode(ImmutableMap.builder()
        .put("method", "Standard")
        .build());
    return Jsons.jsonNode(ImmutableMap.builder()
        .put(JdbcUtils.HOST_KEY, HostPortResolver.resolveHost(container))
        .put(JdbcUtils.PORT_KEY, HostPortResolver.resolvePort(container))
        .put(JdbcUtils.DATABASE_KEY, container.getDatabaseName())
        .put(JdbcUtils.SCHEMAS_KEY, Jsons.jsonNode(schemas))
        .put(JdbcUtils.USERNAME_KEY, username)
        .put(JdbcUtils.PASSWORD_KEY, password)
        .put(JdbcUtils.SSL_KEY, false)
        .put("replication_method", replicationMethod)
        .build());
  }

  @Override
  protected void tearDown(final TestDestinationEnv testEnv) {
    container.close();
  }

  @Override
  protected String getImageName() {
    return "airbyte/source-postgres:dev";
  }

  @Override
  protected ConnectorSpecification getSpec() throws Exception {
    return SshHelpers.getSpecAndInjectSsh();
  }

  @Override
  protected JsonNode getConfig() {
    return config;
  }

  @Override
  protected ConfiguredAirbyteCatalog getConfiguredCatalog() {
    return configCatalog;
  }

  @Override
  protected JsonNode getState() {
    return Jsons.jsonNode(new HashMap<>());
  }

  @Override
  protected boolean supportsPerStream() {
    return true;
  }

  @Test
  public void testFullRefreshWithRevokingSchemaPermissions() throws Exception {
    prepareEnvForUserWithoutPermissions(database);

    config = getConfig(LIMIT_PERMISSION_ROLE, LIMIT_PERMISSION_ROLE_PASSWORD, List.of(LIMIT_PERMISSION_SCHEMA));
    final ConfiguredAirbyteCatalog configuredCatalog = getLimitPermissionConfiguredCatalog();

    final List<AirbyteRecordMessage> fullRefreshRecords = filterRecords(runRead(configuredCatalog));
    final String assertionMessage = "Expected records after full refresh sync for user with schema permission";
    assertFalse(fullRefreshRecords.isEmpty(), assertionMessage);

    revokeSchemaPermissions(database);

    final List<AirbyteRecordMessage> lessPermFullRefreshRecords = filterRecords(runRead(configuredCatalog));
    final String assertionMessageWithoutPermission = "Expected no records after full refresh sync for user without schema permission";
    assertTrue(lessPermFullRefreshRecords.isEmpty(), assertionMessageWithoutPermission);

  }

  @Test
  public void testDiscoverWithRevokingSchemaPermissions() throws Exception {
    prepareEnvForUserWithoutPermissions(database);
    revokeSchemaPermissions(database);
    config = getConfig(LIMIT_PERMISSION_ROLE, LIMIT_PERMISSION_ROLE_PASSWORD, List.of(LIMIT_PERMISSION_SCHEMA));

    runDiscover();
    AirbyteCatalog lastPersistedCatalogSecond = getLastPersistedCatalog();
    final String assertionMessageWithoutPermission = "Expected no streams after discover for user without schema permissions";
    assertTrue(lastPersistedCatalogSecond.getStreams().isEmpty(), assertionMessageWithoutPermission);
  }

  private void revokeSchemaPermissions(Database database) throws SQLException {
    database.query(ctx -> {
      ctx.fetch(String.format("REVOKE USAGE ON schema %s FROM %s;", LIMIT_PERMISSION_SCHEMA, LIMIT_PERMISSION_ROLE));
      return null;
    });
  }

  private void prepareEnvForUserWithoutPermissions(Database database) throws SQLException {
    database.query(ctx -> {
      ctx.fetch(String.format("CREATE ROLE %s WITH LOGIN PASSWORD '%s';", LIMIT_PERMISSION_ROLE, LIMIT_PERMISSION_ROLE_PASSWORD));
      ctx.fetch(String.format("CREATE SCHEMA %s;", LIMIT_PERMISSION_SCHEMA));
      ctx.fetch(String.format("GRANT CONNECT ON DATABASE test TO %s;", LIMIT_PERMISSION_ROLE));
      ctx.fetch(String.format("GRANT USAGE ON schema %s TO %s;", LIMIT_PERMISSION_SCHEMA, LIMIT_PERMISSION_ROLE));
      ctx.fetch(String.format("CREATE TABLE %s.id_and_name(id INTEGER, name VARCHAR(200));", LIMIT_PERMISSION_SCHEMA));
      ctx.fetch(String.format("INSERT INTO %s.id_and_name (id, name) VALUES (1,'picard'),  (2, 'crusher'), (3, 'vash');", LIMIT_PERMISSION_SCHEMA));
      ctx.fetch(String.format("GRANT SELECT ON table %s.id_and_name TO %s;", LIMIT_PERMISSION_SCHEMA, LIMIT_PERMISSION_ROLE));
      return null;
    });
  }

  private ConfiguredAirbyteCatalog getCommonConfigCatalog() {
    return new ConfiguredAirbyteCatalog().withStreams(Lists.newArrayList(
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withCursorField(Lists.newArrayList("id"))
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                STREAM_NAME,
                Field.of("id", JsonSchemaType.NUMBER),
                Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL))),
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withCursorField(Lists.newArrayList("id"))
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                STREAM_NAME2,
                Field.of("id", JsonSchemaType.NUMBER),
                Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL))),
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withCursorField(Lists.newArrayList("id"))
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                STREAM_NAME_MATERIALIZED_VIEW,
                Field.of("id", JsonSchemaType.NUMBER),
                Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL)))));
  }

  private ConfiguredAirbyteCatalog getLimitPermissionConfiguredCatalog() {
    return new ConfiguredAirbyteCatalog().withStreams(Lists.newArrayList(
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withCursorField(Lists.newArrayList("id"))
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                LIMIT_PERMISSION_SCHEMA + "." + "id_and_name",
                Field.of("id", JsonSchemaType.NUMBER),
                Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL)))));
  }

}
