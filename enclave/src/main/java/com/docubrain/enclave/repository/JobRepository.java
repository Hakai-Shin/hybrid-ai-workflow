package com.docubrain.enclave.repository;

import com.docubrain.enclave.model.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Entity
@Table(name = "jobs")
class JobEntity {
    @Id
    String jobId;

    String sourceId;

    @Enumerated(EnumType.STRING)
    JobStatus status;

    @Column(columnDefinition = "TEXT")
    String errorMessage;

    Instant createdAt;
    Instant updatedAt;
}

@Repository
public interface JobRepository extends JpaRepository<JobEntity, String> {

    default void save(String jobId, String sourceId, JobStatus status) {
        JobEntity entity = new JobEntity();
        entity.jobId = jobId;
        entity.sourceId = sourceId;
        entity.status = status;
        entity.createdAt = Instant.now();
        entity.updatedAt = Instant.now();
        save(entity);
    }

    default void updateStatus(String jobId, JobStatus status) {
        findById(jobId).ifPresent(e -> {
            e.status = status;
            e.updatedAt = Instant.now();
            save(e);
        });
    }

    default void updateStatus(String jobId, JobStatus status, String errorMessage) {
        findById(jobId).ifPresent(e -> {
            e.status = status;
            e.errorMessage = errorMessage;
            e.updatedAt = Instant.now();
            save(e);
        });
    }

    default JobStatus getStatus(String jobId) {
        return findById(jobId).map(e -> e.status).orElse(null);
    }
}
