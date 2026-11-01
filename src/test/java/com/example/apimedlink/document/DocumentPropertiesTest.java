package com.example.apimedlink.document;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * MLink-123/125: the document settings are validated when the application starts. A missing or
 * malformed key is a startup failure (a document would be sealed by a key nobody can use); a plain
 * HTTP object store is refused when TLS is required; the API never accepts more than 10 MiB.
 */
class DocumentPropertiesTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String SHORT_KEY = Base64.getEncoder().encodeToString(new byte[16]);

    @Test
    void validSettingsPass() {
        assertThatCode(() -> valid().validate()).doesNotThrowAnyException();
    }

    @Test
    void plainHttpIsRefusedWhenTlsIsRequired() {
        DocumentProperties properties = properties(
                storage("http://minio.example.test", true),
                encryption(1, Map.of(1, KEY)));

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plainHttpIsAllowedOnlyWhenTlsIsNotRequired() {
        DocumentProperties properties = properties(
                storage("http://localhost:9100", false),
                encryption(1, Map.of(1, KEY)));

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void aBlankEndpointOrBucketIsRefused() {
        assertThatThrownBy(() -> properties(storage(" ", false), encryption(1, Map.of(1, KEY))).validate())
                .isInstanceOf(IllegalArgumentException.class);

        DocumentProperties.Storage blankBucket = new DocumentProperties.Storage(
                "https://minio.example.test", "key", "secret", " ", false, true);
        assertThatThrownBy(() -> properties(blankBucket, encryption(1, Map.of(1, KEY))).validate())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMaximumFileSizeIsTenMebibytes() {
        DocumentProperties atLimit = properties(storage("https://minio.example.test", true),
                encryption(1, Map.of(1, KEY)), DataSize.ofMegabytes(10));
        DocumentProperties overLimit = properties(storage("https://minio.example.test", true),
                encryption(1, Map.of(1, KEY)), DataSize.ofBytes(10L * 1024 * 1024 + 1));

        assertThatCode(atLimit::validate).doesNotThrowAnyException();
        assertThatThrownBy(overLimit::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingMalformedOrWrongSizedKeysAreRefusedWithoutEchoingThem() {
        assertThatThrownBy(() -> properties(storage("https://minio.example.test", true),
                encryption(1, Map.of())).validate())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> properties(storage("https://minio.example.test", true),
                encryption(2, Map.of(1, KEY))).validate())
                .isInstanceOf(IllegalArgumentException.class);

        String malformed = "this-is-not-base64";
        assertThatThrownBy(() -> properties(storage("https://minio.example.test", true),
                encryption(1, Map.of(1, malformed))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(malformed);

        assertThatThrownBy(() -> properties(storage("https://minio.example.test", true),
                encryption(1, Map.of(1, SHORT_KEY))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(SHORT_KEY);
    }

    @Test
    void theKeyIs32BytesOfBase64() {
        String valid = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

        assertThatCode(() -> properties(storage("https://minio.example.test", true),
                encryption(1, Map.of(1, valid))).validate())
                .doesNotThrowAnyException();
    }

    private static DocumentProperties valid() {
        return properties(storage("https://minio.example.test", true), encryption(1, Map.of(1, KEY)));
    }

    private static DocumentProperties properties(DocumentProperties.Storage storage,
            DocumentProperties.Encryption encryption) {
        return properties(storage, encryption, DataSize.ofMegabytes(10));
    }

    private static DocumentProperties properties(DocumentProperties.Storage storage,
            DocumentProperties.Encryption encryption, DataSize maxSize) {
        return new DocumentProperties(maxSize, storage, encryption);
    }

    private static DocumentProperties.Storage storage(String endpoint, boolean requireTls) {
        return new DocumentProperties.Storage(endpoint, "access", "secret", "bucket", false, requireTls);
    }

    private static DocumentProperties.Encryption encryption(int activeVersion, Map<Integer, String> keys) {
        return new DocumentProperties.Encryption(activeVersion, keys);
    }
}
