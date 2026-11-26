package com.example.apimedlink.document;

import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * MLink-123/125: {@code app.documents.*}. The settings are checked when the application starts: a
 * missing or malformed encryption key would make every uploaded document unreadable, and an object
 * store reached over plain HTTP is refused when TLS is required. The key values are never echoed in
 * an error message.
 */
@ConfigurationProperties("app.documents")
public final class DocumentProperties {

    /** The API never accepts a file larger than this. */
    public static final DataSize MAX_FILE_SIZE = DataSize.ofMegabytes(10);
    private static final int KEY_BYTES = 32;

    private final DataSize maxSize;
    private final Storage storage;
    private final Encryption encryption;

    public DocumentProperties(DataSize maxSize, Storage storage, Encryption encryption) {
        this.maxSize = maxSize;
        this.storage = storage;
        this.encryption = encryption;
    }

    public DataSize getMaxSize() {
        return maxSize;
    }

    public Storage getStorage() {
        return storage;
    }

    public Encryption getEncryption() {
        return encryption;
    }

    /**
     * @throws IllegalArgumentException when a setting would make documents unreadable or let the API
     *     accept more than {@link #MAX_FILE_SIZE}
     */
    public void validate() {
        if (maxSize == null || maxSize.toBytes() <= 0) {
            throw new IllegalArgumentException("app.documents.max-size must be positive");
        }
        if (maxSize.compareTo(MAX_FILE_SIZE) > 0) {
            throw new IllegalArgumentException("app.documents.max-size must not exceed 10 MiB");
        }
        if (storage == null) {
            throw new IllegalArgumentException("app.documents.storage is missing");
        }
        String endpoint = blankToNull(storage.endpoint());
        if (endpoint == null) {
            throw new IllegalArgumentException("app.documents.storage.endpoint is missing");
        }
        if (blankToNull(storage.bucket()) == null) {
            throw new IllegalArgumentException("app.documents.storage.bucket is missing");
        }
        if (storage.requireTls() && !endpoint.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException(
                    "app.documents.storage.endpoint must use https:// when app.documents.storage.require-tls is true");
        }
        if (encryption == null) {
            throw new IllegalArgumentException("app.documents.encryption is missing");
        }
        encryption.validate();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Where the encrypted documents live. */
    public record Storage(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket,
            boolean createBucket,
            boolean requireTls) {}

    /**
     * The AES-256 keys, by version, and the version used for new documents. Rotating means adding a
     * new version and moving {@code active-version}; old documents keep their version.
     */
    public record Encryption(int activeVersion, Map<Integer, String> keys) {

        /**
         * @throws IllegalArgumentException when the active key is missing, or a key is not 32 bytes
         *     of base64; the message never contains a key
         */
        public void validate() {
            if (activeVersion < 1 || activeVersion > 255) {
                throw new IllegalArgumentException(
                        "app.documents.encryption.active-version must be between 1 and 255");
            }
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("app.documents.encryption.keys is missing");
            }
            String active = keys.get(activeVersion);
            if (active == null || active.isBlank()) {
                throw new IllegalArgumentException(
                        "app.documents.encryption.keys." + activeVersion + " is missing");
            }
            for (Map.Entry<Integer, String> entry : keys.entrySet()) {
                validateKey(entry.getKey(), entry.getValue());
            }
        }

        private static void validateKey(Integer version, String value) {
            if (version == null || version < 1 || version > 255) {
                throw new IllegalArgumentException(
                        "app.documents.encryption.keys must use versions between 1 and 255");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "app.documents.encryption.keys." + version + " is missing");
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(value.trim());
            } catch (IllegalArgumentException notBase64) {
                throw new IllegalArgumentException(
                        "app.documents.encryption.keys." + version + " must be base64 (32 bytes)");
            }
            if (decoded.length != KEY_BYTES) {
                throw new IllegalArgumentException(
                        "app.documents.encryption.keys." + version + " must decode to 32 bytes");
            }
        }
    }
}
