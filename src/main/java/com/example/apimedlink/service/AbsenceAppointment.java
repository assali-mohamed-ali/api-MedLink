package com.example.apimedlink.service;

import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.Patient;

/** US-21: a booked appointment an absence covers, with its patient (names only are shown), waiting for a decision. */
public record AbsenceAppointment(Appointment appointment, Patient patient) {}
