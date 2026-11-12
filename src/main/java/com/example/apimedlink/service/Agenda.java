package com.example.apimedlink.service;

import com.example.apimedlink.model.Clinic;
import java.util.List;

/** A practitioner's agenda: the clinics they work in, and their slots in the period, earliest first. */
public record Agenda(List<Clinic> clinics, List<AgendaEntry> entries) {}
