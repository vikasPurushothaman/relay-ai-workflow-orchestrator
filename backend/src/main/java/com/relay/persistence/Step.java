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
@Table(name="steps")
@JsonIgnoreType
public class Step {
    @EmbeddedId @NotNull @Valid
    private StepId id;
    @NotNull
    @com.relay.api.OpaqueId
    @Column(name="`node_id`", nullable=false, columnDefinition="VARCHAR(128)")
    private String nodeId;
    @NotNull
    @Size(max=32)
    @Column(name="`node_type`", nullable=false, columnDefinition="VARCHAR(32)")
    private String nodeType;
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`status`", nullable=false, columnDefinition="VARCHAR(32)")
    private StepStatus status;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`wait_reason`", nullable=true, columnDefinition="VARCHAR(32)")
    private WaitReason waitReason;
    @NotNull
    @PositiveOrZero
    @Column(name="`final_attempt`", nullable=false, columnDefinition="BIGINT")
    private Long finalAttempt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`resolved_input`", nullable=true, columnDefinition="JSON")
    private String resolvedInput;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`dispatch_request`", nullable=true, columnDefinition="JSON")
    private String dispatchRequest;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`output`", nullable=true, columnDefinition="JSON")
    private String output;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`error`", nullable=true, columnDefinition="JSON")
    private String error;
    @Size(max=320)
    @Column(name="`idempotency_key`", nullable=true, columnDefinition="VARCHAR(320)")
    private String idempotencyKey;
    @com.relay.api.OpaqueId
    @Column(name="`selected_next_node_id`", nullable=true, columnDefinition="VARCHAR(128)")
    private String selectedNextNodeId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`resume_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant resumeAt;
    @NotNull
    @PositiveOrZero
    @Max(1)
    @Column(name="`ai_repair_count`", nullable=false, columnDefinition="BIGINT")
    private Long aiRepairCount;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`ai_repair_request`", nullable=true, columnDefinition="JSON")
    private String aiRepairRequest;
    @PositiveOrZero
    @Column(name="`tokens_prompt`", nullable=true, columnDefinition="BIGINT")
    private Long tokensPrompt;
    @PositiveOrZero
    @Column(name="`tokens_completion`", nullable=true, columnDefinition="BIGINT")
    private Long tokensCompletion;
    @NotNull
    @Column(name="`ai_usage_complete`", nullable=false, columnDefinition="BOOLEAN")
    private Boolean aiUsageComplete;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`started_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant startedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`finished_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant finishedAt;
    @PositiveOrZero
    @Column(name="`duration_ms`", nullable=true, columnDefinition="BIGINT")
    private Long durationMs;
    protected Step() {}
    public Step(StepId id, String nodeId, String nodeType, StepStatus status, Long finalAttempt, Long aiRepairCount, Boolean aiUsageComplete) {
        this.id = id;
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.status = status;
        this.finalAttempt = finalAttempt;
        this.aiRepairCount = aiRepairCount;
        this.aiUsageComplete = aiUsageComplete;
    }
    public StepId getId() { return id; }
    public String getNodeId() { return nodeId; }
    public String getNodeType() { return nodeType; }
    public StepStatus getStatus() { return status; }
    public WaitReason getWaitReason() { return waitReason; }
    public Long getFinalAttempt() { return finalAttempt; }
    public String getResolvedInput() { return resolvedInput; }
    public String getDispatchRequest() { return dispatchRequest; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSelectedNextNodeId() { return selectedNextNodeId; }
    public Instant getResumeAt() { return resumeAt; }
    public Long getAiRepairCount() { return aiRepairCount; }
    public String getAiRepairRequest() { return aiRepairRequest; }
    public Long getTokensPrompt() { return tokensPrompt; }
    public Long getTokensCompletion() { return tokensCompletion; }
    public Boolean getAiUsageComplete() { return aiUsageComplete; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getDurationMs() { return durationMs; }
}
