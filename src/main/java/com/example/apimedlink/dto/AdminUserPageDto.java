package com.example.apimedlink.dto;

import java.util.List;

/** Body of GET /api/admin/users: one page of accounts and the total number of matches. */
public record AdminUserPageDto(List<AdminUserDto> items, long total) {}