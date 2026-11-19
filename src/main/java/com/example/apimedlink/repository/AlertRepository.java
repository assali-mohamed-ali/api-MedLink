package com.example.apimedlink.repository;

import com.example.apimedlink.model.Alert;
import com.example.apimedlink.model.AlertType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** A practitioner's alerts (MLink-37). */
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    /** One page of a practitioner's unread alerts (partial index idx_alerts_unread), in the page's order. */
    List<Alert> findByPractitionerIdAndReadAtIsNull(UUID practitionerId, Pageable page);

    /** One page of all the alerts of a practitioner, read or not, in the page's order. */
    List<Alert> findByPractitionerId(UUID practitionerId, Pageable page);

    /** The alert if it is this practitioner's: another one's looks like one that does not exist (37.3). */
    Optional<Alert> findByIdAndPractitionerId(UUID id, UUID practitionerId);

    /** Whether this appointment already gave its alert of this type (37.2: one alert per cancellation). */
    boolean existsByTypeAndAppointmentId(AlertType type, UUID appointmentId);
}
