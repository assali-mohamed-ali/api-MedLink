package com.example.apimedlink.model;

/** Why an alert exists (MLink-37). The database checks the same list (V17, V21). */
public enum AlertType {
    /** A patient cancelled one of the practitioner's appointments. */
    APPOINTMENT_CANCELLED,
    /** A patient moved one of the practitioner's appointments to another date: the old slot is free again. */
    APPOINTMENT_MOVED
}
