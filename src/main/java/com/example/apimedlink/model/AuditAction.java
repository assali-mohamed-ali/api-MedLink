package com.example.apimedlink.model;

/**
 * What an audit entry records (MLink-59). Other tasks add their values (MLink-72: account operations;
 * MLink-26: the practitioner's slots).
 */
public enum AuditAction {
    ACCESS_DENIED,
    SLOT_OPENED,
    SLOT_CLOSED
}
