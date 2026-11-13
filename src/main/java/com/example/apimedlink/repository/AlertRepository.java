package com.example.apimedlink.repository;

import com.example.apimedlink.model.Alert;
import com.example.apimedlink.model.AlertType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** A practitioner's alerts (MLink-37). */
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    /** The unread alerts of a practitioner, newest first (partial index idx_alerts_unread). */
    List<Alert> findByPractitionerIdAndReadAtIsNullOrderByCreatedAtDesc(UUID practitionerId);

    /** All the alerts of a practitioner, read or not, newest first. */
    List<Alert> findByPractitionerIdOrderByCreatedAtDesc(UUID practitionerId);

    /** The alert if it is this practitioner's: another one's looks like one that does not exist (37.3). */
    Optional<Alert> findByIdAndPractitionerId(UUID id, UUID practitionerId);

    /** Whether this appointment already gave its alert of this type (37.2: one alert per cancellation). */
    boolean existsByTypeAndAppointmentId(AlertType type, UUID appointmentId);
}
