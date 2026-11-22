package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.document.AesDocumentCipher;
import com.example.apimedlink.document.DocumentProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MLink-123/125: like the staff-code key, the document encryption key is mandatory. The application
 * must not start with no key, a malformed key or a key of the wrong size, and the failure must name
 * the property, never echo the key.
 */
class DocumentKeyStartupTest {

    /** 32 bytes, base64: a test value only, never a real key. */
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    final ApplicationContextRunner app = new ApplicationContextRunner()
            .withUserConfiguration(DocumentStartupConfig.class);

    @Test
    void aValidKeyStartsTheDocumentCipher() {
        app.withPropertyValues(properties(KEY))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AesDocumentCipher.class);
                });
    }

    @Test
    void noKeyStopsTheStartAndNamesTheEncryptionSetting() {
        app.withPropertyValues(storageProperties())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalArgumentException.class);
                    assertThat(rootCause(context.getStartupFailure()).getMessage())
                            .containsIgnoringCase("encryption");
                });
    }

    @Test
    void aMalformedKeyStopsTheStartWithoutEchoingIt() {
        String malformed = "this-is-not-base64";
        app.withPropertyValues(properties(malformed))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalArgumentException.class);
                    assertThat(context.getStartupFailure().toString()).doesNotContain(malformed);
                });
    }

    @Test
    void aKeyOfTheWrongSizeStopsTheStartWithoutEchoingIt() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        app.withPropertyValues(properties(shortKey))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure())).isInstanceOf(IllegalArgumentException.class);
                    assertThat(context.getStartupFailure().toString()).doesNotContain(shortKey);
                });
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String[] properties(String key) {
        String[] storage = storageProperties();
        String[] all = new String[storage.length + 2];
        System.arraycopy(storage, 0, all, 0, storage.length);
        all[storage.length] = "app.documents.encryption.active-version=1";
        all[storage.length + 1] = "app.documents.encryption.keys.1=" + key;
        return all;
    }

    private static String[] storageProperties() {
        return new String[] {
            "app.documents.max-size=10MB",
            "app.documents.storage.endpoint=https://minio.example.test",
            "app.documents.storage.access-key=access",
            "app.documents.storage.secret-key=secret",
            "app.documents.storage.bucket=bucket",
            "app.documents.storage.create-bucket=false",
            "app.documents.storage.require-tls=true"
        };
    }

    /** The production wiring this test assumes: the properties bean validates before the cipher exists. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentProperties.class)
    static class DocumentStartupConfig {

        @Bean
        AesDocumentCipher documentCipher(DocumentProperties properties) {
            properties.validate();
            return new AesDocumentCipher(properties.getEncryption());
        }
    }
}
