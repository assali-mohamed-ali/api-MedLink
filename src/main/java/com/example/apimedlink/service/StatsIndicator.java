package com.example.apimedlink.service;

import com.example.apimedlink.repository.AppointmentFact;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The statistics that can be exported (MLink-95). Each one counts appointments (any status) by one group. Only
 * counts leave the service: no name, no identifier of a person, no contact detail is ever read.
 */
public enum StatsIndicator {
    /** Appointments per day of the slot (UTC). */
    APPOINTMENTS_PER_DAY(fact -> fact.startsAt().atZone(ZoneOffset.UTC).toLocalDate().toString()),
    /** Appointments per status (BOOKED, CANCELLED). */
    APPOINTMENTS_PER_STATUS(fact -> fact.status().name()),
    /** Appointments per specialty of the practitioner. */
    APPOINTMENTS_PER_SPECIALTY(AppointmentFact::specialty);

    private final Function<AppointmentFact, String> group;

    StatsIndicator(Function<AppointmentFact, String> group) {
        this.group = group;
    }

    /** The number of appointments in each group, groups in alphabetical order. */
    Map<String, Long> count(List<AppointmentFact> facts) {
        return facts.stream().collect(Collectors.groupingBy(group, TreeMap::new, Collectors.counting()));
    }
}
