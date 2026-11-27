package com.example.apimedlink.service;

import com.example.apimedlink.model.Absence;
import java.util.List;

/** US-21: a declared absence, the free slots it closed and the booked appointments that now wait for a decision. */
public record DeclaredAbsence(Absence absence, int closedFreeSlots, List<AbsenceAppointment> toDecide) {}
