package com.example.apimedlink.document;

import java.util.List;

/** One page of a patient's documents, newest first. */
public record DocumentPageDto(List<DocumentDto> items, int page, int size, long total) {}
