package com.example.apimedlink.document;

/** The stored form of a document: ciphertext plus the key version needed to open it. */
public record EncryptedDocument(byte[] bytes, int keyVersion) {}
