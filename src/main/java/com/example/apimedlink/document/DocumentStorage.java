package com.example.apimedlink.document;

/**
 * MLink-123/125: the bytes of a document live outside Postgres. Implementations must treat the key as
 * opaque and never log an object's content.
 */
public interface DocumentStorage {

    void store(String key, byte[] bytes);

    byte[] load(String key);

    void delete(String key);

    boolean exists(String key);
}
