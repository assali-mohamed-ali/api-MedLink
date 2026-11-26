package com.example.apimedlink.service;

import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Specialty;
import java.util.List;

/** What a practitioner's profile is chosen from in the back-office (MLink-69): every clinic and specialty. */
public record ProfileChoices(List<Clinic> clinics, List<Specialty> specialties) {}
