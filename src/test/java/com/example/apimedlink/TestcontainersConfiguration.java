package com.example.apimedlink;

import java.time.Duration;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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

    /**
     * For {@code spring.flyway.user/password} in the same tests (GitHub #57: required there, and different from
     * the datasource user). Never used to connect: the service connection gives Flyway the container's owner.
     */
    public static final String UNUSED_OWNER_CREDENTIAL = "unused-container-owner-runs-flyway";

    /** Test-only MinIO credentials and bucket (the same values the document test base publishes). */
    public static final String MINIO_ACCESS_KEY = "medlink-test";
    public static final String MINIO_SECRET_KEY = "medlink-test-secret";
    public static final String MINIO_BUCKET = "medlink-documents";
    public static final int MINIO_PORT = 9000;
    public static final String MINIO_HEALTH_PATH = "/minio/health/live";
    /** Pinned by digest: the tests run the exact approved image, never a floating tag. */
    public static final String MINIO_IMAGE =
            "ghcr.io/coollabsio/minio@sha256:69b55a1c1c5dc285ce04db96689f5b2102317fc77a50680a1874ca6efd1c87f9";

    private static GenericContainer<?> minio;

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

    /**
     * The MinIO used by document functional tests. It is started lazily: a functional test that has
     * nothing to do with documents never pays for it. The container is not a Spring bean and has no
     * service connection; document tests publish its endpoint themselves.
     */
    public static synchronized String minioEndpoint() {
        if (minio == null) {
            minio = newMinioContainer();
            minio.start();
        }
        return endpoint(minio);
    }

    /** A fresh, unstarted MinIO for a test that needs its own instance (failure injection). */
    public static GenericContainer<?> newMinioContainer() {
        return new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                .withEnv("MINIO_BROWSER", "off")
                .withExposedPorts(MINIO_PORT)
                .waitingFor(Wait.forHttp(MINIO_HEALTH_PATH)
                        .forPort(MINIO_PORT)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    public static String endpoint(GenericContainer<?> container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(MINIO_PORT);
    }
}
