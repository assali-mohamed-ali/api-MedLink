package com.example.apimedlink;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * For {@code spring.datasource.username/password} in tests that run the staging or prod profile: those
     * profiles have no default and refuse to start without a value (GitHub #64, DatabaseCredentialsCheck). Never
     * used to connect: {@code @ServiceConnection} gives the container's own user and password.
     */
    public static final String UNUSED_DB_CREDENTIAL = "unused-container-gives-its-own";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
    }

}
