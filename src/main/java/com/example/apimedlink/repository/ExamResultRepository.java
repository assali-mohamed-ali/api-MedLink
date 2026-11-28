package com.example.apimedlink.repository;

import com.example.apimedlink.model.ExamResult;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** US-13 / US-25: examination results, saved once, read per patient. */
public interface ExamResultRepository extends JpaRepository<ExamResult, UUID> {

    List<ExamResult> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    /** US-13: one page of the patient's results; the caller's pageable sorts newest first. */
    Page<ExamResult> findByPatientId(UUID patientId, Pageable page);

    /** US-25: one page of what this practitioner published for the patient. */
    Page<ExamResult> findByPractitionerIdAndPatientId(UUID practitionerId, UUID patientId, Pageable page);

    long countByPatientIdAndReadAtIsNull(UUID patientId);

    /** US-25: the result a repeated submission names. */
    Optional<ExamResult> findByPractitionerIdAndRequestId(UUID practitionerId, UUID requestId);

    /** Marks these unseen results of the patient as seen. Native: the entity is immutable for Hibernate. */
    @Modifying
    @Query(value = "update exam_results set read_at = :now where patient_id = :patientId and id in (:ids) "
            + "and read_at is null", nativeQuery = true)
    int markRead(@Param("patientId") UUID patientId, @Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /** US-25: results whose notice is still to settle, oldest first, published after {@code since}. */
    @Query(value = "select id from exam_results where notice_sent_at is null and created_at > :since "
            + "order by created_at limit 50", nativeQuery = true)
    List<UUID> findPendingNotices(@Param("since") Instant since);

    /** US-25: the result's row, locked, while its notice is still to settle; empty if settled or held by another run. */
    @Query(value = "select id from exam_results where id = :id and notice_sent_at is null for update skip locked",
            nativeQuery = true)
    Optional<UUID> lockPendingNotice(@Param("id") UUID id);

    @Modifying
    @Query(value = "update exam_results set notice_sent_at = :now where id = :id and notice_sent_at is null",
            nativeQuery = true)
    int settleNotice(@Param("id") UUID id, @Param("now") Instant now);
}
