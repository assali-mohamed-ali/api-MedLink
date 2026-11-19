package com.example.apimedlink.model;

/**
 * What an audit entry records (MLink-59). Other tasks add their values (MLink-72: account operations;
 * MLink-26: the practitioner's slots).
 */
public enum AuditAction {
    ACCESS_DENIED,
    /** A patient booked a slot; the resource is the appointment (MLink-104). */
    APPOINTMENT_BOOKED,
    /** The holder cancelled their appointment; the resource is the appointment (MLink-106, review on #120). */
    APPOINTMENT_CANCELLED,
    /** The holder moved their appointment; the resource is the new appointment (MLink-107, review on #120). */
    APPOINTMENT_RESCHEDULED,
    SLOT_OPENED,
    SLOT_CLOSED,
    /** Anonymised statistics were exported, or the export was refused (MLink-95): the period and indicators only. */
    STATS_EXPORTED,
    /** A practitioner read a patient's record (MLink-31): ids only, never medical content. */
    RECORD_VIEWED,
    /** A practitioner wrote a consultation report (MLink-32): the report id only, never its text. */
    REPORT_CREATED,
    /** A practitioner read a patient's consultation reports (MLink-32): the patient id only. */
    REPORT_VIEWED,
    /** Staff uploaded a patient document (MLink-123/125): the document id only. */
    DOCUMENT_UPLOAD,
    /** A patient or staff member read a patient document (MLink-123/125): the document id only. */
    DOCUMENT_READ
}
