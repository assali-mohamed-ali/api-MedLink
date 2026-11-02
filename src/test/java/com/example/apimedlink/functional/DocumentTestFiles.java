package com.example.apimedlink.functional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Test bytes for MLink-123/125. The API identifies a file by its magic bytes, not by the client's
 * content type or extension, so these are the smallest well-formed headers of each accepted type.
 */
public final class DocumentTestFiles {

    public static final byte[] PDF = ("%PDF-1.7\n"
                    + "1 0 obj\n<</Type/Catalog/Pages 2 0 R>>\nendobj\n"
                    + "2 0 obj\n<</Type/Pages/Count 0>>\nendobj\n"
                    + "trailer\n<</Root 1 0 R>>\n%%EOF\n")
            .getBytes(StandardCharsets.US_ASCII);

    /** 1x1 transparent PNG. */
    static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    /** Small JPEG (SOI … EOI). */
    /** Minimal JPEG stream header: the API only needs the first three magic bytes. */
    static final byte[] JPEG = {
        (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0,
        0x00, 0x10, 'J', 'F', 'I', 'F', 0x00
    };

    static final byte[] UNSUPPORTED = "GIF89a\nnot a document".getBytes(StandardCharsets.US_ASCII);

    static final int TEN_MEBIBYTES = 10 * 1024 * 1024;

    private DocumentTestFiles() {}

    /** A PDF of exactly {@code size} bytes (the header stays valid, the rest is zero padding). */
    static byte[] pdfOfSize(int size) {
        byte[] bytes = new byte[size];
        System.arraycopy(PDF, 0, bytes, 0, Math.min(PDF.length, size));
        return bytes;
    }
}
