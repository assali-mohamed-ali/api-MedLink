package com.example.apimedlink.model;

/** What an audit entry targets. Stored as its name, so these values are part of the trail's contract. */
public enum AuditResourceType {
    REQUEST,
    /** A patient (the resource id is the patient id): a record, report list or document list read. */
    PATIENT,
    PATIENT_DOCUMENT
}
