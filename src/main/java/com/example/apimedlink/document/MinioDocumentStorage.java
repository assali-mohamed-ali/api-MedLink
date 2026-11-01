package com.example.apimedlink.document;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MLink-123/125: the encrypted documents in MinIO (S3 API). The client is built without contacting
 * the server; the bucket is checked (and created when allowed) on the first operation. Failures
 * become a {@link StorageException}: the client never sees an object-store response or a credential,
 * and no document content is ever logged.
 */
public class MinioDocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioDocumentStorage.class);
    private static final String NO_SUCH_KEY = "NoSuchKey";
    private static final String NO_SUCH_OBJECT = "NoSuchObject";
    private static final String BUCKET_ALREADY_EXISTS = "BucketAlreadyExists";
    private static final String BUCKET_ALREADY_OWNED_BY_YOU = "BucketAlreadyOwnedByYou";

    private final MinioClient client;
    private final String bucket;
    private final boolean createBucket;
    private volatile boolean bucketReady;

    public MinioDocumentStorage(DocumentProperties.Storage storage) {
        this.client = MinioClient.builder()
                .endpoint(storage.endpoint())
                .credentials(storage.accessKey(), storage.secretKey())
                .build();
        this.bucket = storage.bucket();
        this.createBucket = storage.createBucket();
    }

    @Override
    public void store(String key, byte[] bytes) {
        ensureBucket();
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/octet-stream")
                    .build());
        } catch (Exception failure) {
            log.error("MinIO put failed for object {}", key);
            throw new StorageException("Le stockage des documents est indisponible.", failure);
        }
    }

    @Override
    public byte[] load(String key) {
        ensureBucket();
        try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build())) {
            return response.readAllBytes();
        } catch (Exception failure) {
            log.error("MinIO get failed for object {}", key);
            throw new StorageException("Le document est indisponible.", failure);
        }
    }

    @Override
    public void delete(String key) {
        ensureBucket();
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (ErrorResponseException alreadyGone) {
            if (!isMissing(alreadyGone)) {
                log.error("MinIO delete failed for object {}", key);
                throw new StorageException("Le document n'a pas pu être supprimé.", alreadyGone);
            }
        } catch (Exception failure) {
            log.error("MinIO delete failed for object {}", key);
            throw new StorageException("Le document n'a pas pu être supprimé.", failure);
        }
    }

    @Override
    public boolean exists(String key) {
        ensureBucket();
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
            return true;
        } catch (ErrorResponseException missing) {
            if (isMissing(missing)) {
                return false;
            }
            log.error("MinIO stat failed for object {}", key);
            throw new StorageException("Le stockage des documents est indisponible.", missing);
        } catch (Exception failure) {
            log.error("MinIO stat failed for object {}", key);
            throw new StorageException("Le stockage des documents est indisponible.", failure);
        }
    }

    private synchronized void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                if (!createBucket) {
                    throw new StorageException("Le bucket des documents est absent.");
                }
                try {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                } catch (ErrorResponseException alreadyCreated) {
                    String code = alreadyCreated.errorResponse() == null
                            ? null : alreadyCreated.errorResponse().code();
                    if (!BUCKET_ALREADY_EXISTS.equals(code) && !BUCKET_ALREADY_OWNED_BY_YOU.equals(code)) {
                        throw alreadyCreated;
                    }
                }
            }
            bucketReady = true;
        } catch (StorageException failure) {
            throw failure;
        } catch (Exception failure) {
            log.error("MinIO bucket check failed for bucket {}", bucket);
            throw new StorageException("Le stockage des documents est indisponible.", failure);
        }
    }

    private static boolean isMissing(ErrorResponseException failure) {
        String code = failure.errorResponse() == null ? null : failure.errorResponse().code();
        return NO_SUCH_KEY.equals(code) || NO_SUCH_OBJECT.equals(code);
    }
}
