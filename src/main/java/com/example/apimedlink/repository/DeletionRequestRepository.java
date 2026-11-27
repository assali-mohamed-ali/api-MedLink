package com.example.apimedlink.repository;

import com.example.apimedlink.model.DeletionRequest;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** US-16: the patients' deletion requests, looked up by the patient's id. */
public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, UUID> {}
