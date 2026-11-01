package com.example.apimedlink.document;

/** The plaintext of one document and what the download response needs to describe it. */
public record DownloadedDocument(byte[] bytes, String contentType, String originalFilename) {}
