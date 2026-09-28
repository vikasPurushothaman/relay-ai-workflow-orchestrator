package com.relay.persistence;

import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.annotation.JsonIgnoreType;

/** Internal persistence record. Expose explicit DTOs, never this entity. */
@Entity
@Table(name="queue_jobs")
@JsonIgnoreType
public class QueueJob {
    @Id
    @NotNull
    @com.relay.api.OpaqueId
    @Column(name="`run_id`", nullable=false, columnDefinition="VARCHAR(128)", updatable=false)
    private String runId;
    @Positive
    @Column(name="`step_sequence`", nullable=true, columnDefinition="BIGINT")
    private Long stepSequence;
    @NotNull
    @com.relay.api.OpaqueId
    @Column(name="`target_node_id`", nullable=false, columnDefinition="VARCHAR(128)")
    private String targetNodeId;
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`status`", nullable=false, columnDefinition="VARCHAR(32)")
    private JobStatus status;
    @NotNull
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`available_at`", nullable=false, columnDefinition="DATETIME(6)")
    private Instant availableAt;
    @Column(name="`lease_owner`", nullable=true, columnDefinition="TEXT")
    private String leaseOwner;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`lease_until`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant leaseUntil;
    @NotNull
    @PositiveOrZero
    @Column(name="`claim_generation`", nullable=false, columnDefinition="BIGINT")
    private Long claimGeneration;
    @NotNull
    @PositiveOrZero
    @Column(name="`retry_count`", nullable=false, columnDefinition="BIGINT")
    private Long retryCount;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`next_attempt_cause`", nullable=true, columnDefinition="VARCHAR(32)")
    private AttemptCause nextAttemptCause;
    @NotNull
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`created_at`", nullable=false, columnDefinition="DATETIME(6)")
    private Instant createdAt;
    @NotNull
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`updated_at`", nullable=false, columnDefinition="DATETIME(6)")
    private Instant updatedAt;
    protected QueueJob() {}
    public QueueJob(String runId, String targetNodeId, JobStatus status, Instant availableAt, Long claimGeneration, Long retryCount, Instant createdAt, Instant updatedAt) {
        this.runId = runId;
        this.targetNodeId = targetNodeId;
        this.status = status;
        this.availableAt = availableAt;
        this.claimGeneration = claimGeneration;
        this.retryCount = retryCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    public static QueueJob initial(String runId,String entry,Instant at) {
        var job=new QueueJob(runId,entry,JobStatus.ready,at,0L,0L,at,at);
        job.nextAttemptCause=AttemptCause.initial;
        return job;
    }
    public String getRunId() { return runId; }
    public Long getStepSequence() { return stepSequence; }
    public String getTargetNodeId() { return targetNodeId; }
    public JobStatus getStatus() { return status; }
    public Instant getAvailableAt() { return availableAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Long getClaimGeneration() { return claimGeneration; }
    public Long getRetryCount() { return retryCount; }
    public AttemptCause getNextAttemptCause() { return nextAttemptCause; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
