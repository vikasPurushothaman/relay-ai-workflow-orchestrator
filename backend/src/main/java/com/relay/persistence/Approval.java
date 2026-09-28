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
@Table(name="approvals")
@JsonIgnoreType
public class Approval {
    @Id
    @NotNull
    @com.relay.api.OpaqueId
    @Column(name="`id`", nullable=false, columnDefinition="VARCHAR(128)", updatable=false)
    private String id;
    @NotNull
    @com.relay.api.OpaqueId
    @Column(name="`run_id`", nullable=false, columnDefinition="VARCHAR(128)")
    private String runId;
    @NotNull
    @Positive
    @Column(name="`step_sequence`", nullable=false, columnDefinition="BIGINT")
    private Long stepSequence;
    @NotNull
    @com.relay.api.OpaqueId
    @Column(name="`node_id`", nullable=false, columnDefinition="VARCHAR(128)")
    private String nodeId;
    @NotNull
    @Column(name="`message`", nullable=false, columnDefinition="LONGTEXT")
    private String message;
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`status`", nullable=false, columnDefinition="VARCHAR(32)")
    private ApprovalStatus status;
    @NotNull
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`created_at`", nullable=false, columnDefinition="DATETIME(6)")
    private Instant createdAt;
    @Column(name="`decided_by`", nullable=true, columnDefinition="TEXT")
    private String decidedBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`decided_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant decidedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`closed_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant closedAt;
    @Column(name="`close_reason`", nullable=true, columnDefinition="TEXT")
    private String closeReason;
    protected Approval() {}
    public Approval(String id, String runId, Long stepSequence, String nodeId, String message, ApprovalStatus status, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.stepSequence = stepSequence;
        this.nodeId = nodeId;
        this.message = message;
        this.status = status;
        this.createdAt = createdAt;
    }
    public String getId() { return id; }
    public String getRunId() { return runId; }
    public Long getStepSequence() { return stepSequence; }
    public String getNodeId() { return nodeId; }
    public String getMessage() { return message; }
    public ApprovalStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getClosedAt() { return closedAt; }
    public String getCloseReason() { return closeReason; }
    /** Called inside the later decision transaction after run/step guards; actor is never request input. */
    public void recordHumanDecision(boolean approve, java.time.Instant at) {
        String actor = com.relay.security.ManagementAuthentication.requireActor();
        if (status != ApprovalStatus.pending || decidedAt != null || closedAt != null) {
            throw new com.relay.api.ApiFailure(com.relay.api.ApiFailure.Reason.CONFLICT);
        }
        if (at == null || at.isBefore(createdAt)) {
            throw new com.relay.api.ApiFailure(com.relay.api.ApiFailure.Reason.INVALID_INPUT);
        }
        status = approve ? ApprovalStatus.approved : ApprovalStatus.rejected;
        decidedBy = actor;
        decidedAt = at;
    }
}
