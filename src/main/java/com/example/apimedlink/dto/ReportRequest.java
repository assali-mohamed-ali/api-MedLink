package com.example.apimedlink.dto;

/**
 * A new consultation report (MLink-32): the text only. The author is the caller and the patient is in the path;
 * nothing is required or validated by Spring here, the service checks the text after the attachment.
 */
public record ReportRequest(String content) {}
