package com.example.apimedlink.service;

import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Specialty;
import java.util.List;

/** What the slot search form offers (MLink-163): entities, sorted; the controller maps them. */
public record SlotFilters(List<Clinic> clinics, List<Specialty> specialties, List<Practitioner> practitioners) {}
