package com.example.apimedlink;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

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
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
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
