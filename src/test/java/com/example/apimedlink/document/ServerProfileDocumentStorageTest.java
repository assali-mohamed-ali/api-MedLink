package com.example.apimedlink.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The staging and prod profiles' object-store switches (MLink-9, option b of 2026-11-22). The servers run their own
 * MinIO on the compose network, over plain HTTP inside the host, so deploy/env/*.env turns TLS off and lets the API
 * create its bucket. Without those settings the profiles stay strict: TLS required, bucket provisioned outside.
 */
class ServerProfileDocumentStorageTest {

    private static final String REQUIRE_TLS = "app.documents.storage.require-tls";
    private static final String CREATE_BUCKET = "app.documents.storage.create-bucket";

    @ParameterizedTest
    @ValueSource(strings = {"staging", "prod"})
    void withoutDeploySettingsTheProfileRequiresTlsAndAnExistingBucket(String profile) throws IOException {
        StandardEnvironment environment = profile(profile, Map.of());

        assertThat(environment.getProperty(REQUIRE_TLS, Boolean.class)).isTrue();
        assertThat(environment.getProperty(CREATE_BUCKET, Boolean.class)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging", "prod"})
    void theHostMinioSettingsOfTheDeployFilesApply(String profile) throws IOException {
        StandardEnvironment environment = profile(profile, Map.of(
                "APP_DOCUMENTS_STORAGE_REQUIRE_TLS", "false",
                "APP_DOCUMENTS_STORAGE_CREATE_BUCKET", "true"));

        assertThat(environment.getProperty(REQUIRE_TLS, Boolean.class)).isFalse();
        assertThat(environment.getProperty(CREATE_BUCKET, Boolean.class)).isTrue();
    }

    /** The profile's YAML under the given environment variables only (not the variables of the machine running it). */
    private static StandardEnvironment profile(String profile, Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("variables", variables));
        new YamlPropertySourceLoader()
                .load(profile, new ClassPathResource("application-" + profile + ".yml"))
                .forEach(sources::addLast);
        return environment;
    }
}
