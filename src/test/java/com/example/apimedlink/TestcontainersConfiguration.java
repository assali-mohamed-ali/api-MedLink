package com.example.apimedlink;

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The tests' PostgreSQL, with the two roles of production (GitHub #57): Flyway migrates as the container's owner,
 * the application connects as the runtime role {@value #APP_ROLE}, created by the same init script as in the
 * compose files. So every test runs with the privileges the API has in production.
 *
 * <p>The service connection is limited to Flyway on purpose: a plain {@code @ServiceConnection} also gives the
 * owner to the datasource. The datasource gets its own connection details instead, a bean that needs the
 * container, so it is ready before anything (the embedded web server's filters included) opens the pool. A
 * {@code DynamicPropertyRegistrar} would come too late for a context with a real port.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** The runtime role of docker/postgres/initdb/10-app-role.sh and V18__app_role_grants.sql. */
    public static final String APP_ROLE = "medlink_app";

    static final String APP_ROLE_PASSWORD = "test-only-app-role-password";

    /**
     * For {@code spring.datasource.username/password} in tests that run the staging or prod profile: those
     * profiles have no default and refuse to start without a value (GitHub #64, DatabaseCredentialsCheck). Never
     * used to connect: the connection details below win over {@code spring.datasource.*}.
     */
    public static final String UNUSED_DB_CREDENTIAL = "unused-container-gives-its-own";

    @Bean
    @ServiceConnection(type = FlywayConnectionDetails.class)
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16"))
                .withEnv("APP_DB_PASSWORD", APP_ROLE_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/postgres/initdb/10-app-role.sh", 0755),
                        "/docker-entrypoint-initdb.d/10-app-role.sh");
    }

    @Bean
    JdbcConnectionDetails runtimeRoleConnection(PostgreSQLContainer postgres) {
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return APP_ROLE;
            }

            @Override
            public String getPassword() {
                return APP_ROLE_PASSWORD;
            }
        };
    }

}
