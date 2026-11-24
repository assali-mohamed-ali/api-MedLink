package com.example.apimedlink.service;

import com.example.apimedlink.repository.AppointmentFact;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The statistics that can be exported (MLink-95). Each one counts appointments (any status) by one group. For each
 * group the service also knows how many distinct patients it holds, to decide whether the group may be released.
 * Only the appointment count leaves the service: no name, no identifier of a person, no contact detail.
 */
public enum StatsIndicator {
    /** Appointments per day of the slot (UTC). */
    APPOINTMENTS_PER_DAY(fact -> fact.startsAt().atZone(ZoneOffset.UTC).toLocalDate().toString()),
    /** Appointments per status (BOOKED, CANCELLED). */
    APPOINTMENTS_PER_STATUS(fact -> fact.status().name()),
    /** Appointments per specialty of the practitioner. */
    APPOINTMENTS_PER_SPECIALTY(AppointmentFact::specialty);

    /** One group: how many appointments, and how many distinct patients they belong to. */
    public record Group(long appointments, long patients) {
    }

    private final Function<AppointmentFact, String> group;

    StatsIndicator(Function<AppointmentFact, String> group) {
        this.group = group;
    }

    /** The appointments and the distinct patients in each group, groups in alphabetical order. */
    Map<String, Group> count(List<AppointmentFact> facts) {
        Map<String, List<AppointmentFact>> byGroup =
                facts.stream().collect(Collectors.groupingBy(group, TreeMap::new, Collectors.toList()));
        Map<String, Group> counts = new TreeMap<>();
        byGroup.forEach((name, rows) -> counts.put(name, new Group(
                rows.size(), rows.stream().map(AppointmentFact::patientId).distinct().count())));
        return counts;
    }
}
