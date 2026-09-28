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
@Table(name="runs")
@JsonIgnoreType
public class Run {
    @Id
    @NotNull
    @com.relay.api.OpaqueId
    @Column(name="`run_id`", nullable=false, columnDefinition="VARCHAR(128)", updatable=false)
    private String runId;
    @NotNull
    @com.relay.api.OpaqueId
    @Column(name="`workflow_id`", nullable=false, columnDefinition="VARCHAR(128)", updatable=false)
    private String workflowId;
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`definition_snapshot`", nullable=false, columnDefinition="JSON", updatable=false)
    private String definitionSnapshot;
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`execution_policy`", nullable=false, columnDefinition="JSON", updatable=false)
    private String executionPolicy;
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`input`", nullable=false, columnDefinition="JSON", updatable=false)
    private String input;
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`trigger_type`", nullable=false, columnDefinition="VARCHAR(32)")
    private TriggerType triggerType;
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`status`", nullable=false, columnDefinition="VARCHAR(32)")
    private RunStatus status;
    @com.relay.api.OpaqueId
    @Column(name="`current_node_id`", nullable=true, columnDefinition="VARCHAR(128)")
    private String currentNodeId;
    @NotNull
    @PositiveOrZero
    @Column(name="`steps_executed`", nullable=false, columnDefinition="BIGINT")
    private Long stepsExecuted;
    @NotNull
    @Positive
    @Column(name="`next_step_sequence`", nullable=false, columnDefinition="BIGINT")
    private Long nextStepSequence;
    @PositiveOrZero
    @Column(name="`ai_tokens_used`", nullable=true, columnDefinition="BIGINT")
    private Long aiTokensUsed;
    @NotNull
    @Column(name="`ai_usage_complete`", nullable=false, columnDefinition="BOOLEAN")
    private Boolean aiUsageComplete;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`error`", nullable=true, columnDefinition="JSON")
    private String error;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`cancel_requested_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant cancelRequestedAt;
    @Column(name="`cancel_requested_by`", nullable=true, columnDefinition="TEXT")
    private String cancelRequestedBy;
    @Column(name="`cancellation_reason`", nullable=true, columnDefinition="TEXT")
    private String cancellationReason;
    @NotNull
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`created_at`", nullable=false, columnDefinition="DATETIME(6)")
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`started_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant startedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`finished_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant finishedAt;
    @Version
    @PositiveOrZero
    @Column(name="`revision`", nullable=false, columnDefinition="BIGINT")
    private Long revision;
    protected Run() {}
    public Run(String runId, String workflowId, String definitionSnapshot, String executionPolicy, String input, TriggerType triggerType, RunStatus status, Long stepsExecuted, Long nextStepSequence, Boolean aiUsageComplete, Instant createdAt) {
        this.runId = runId;
        this.workflowId = workflowId;
        this.definitionSnapshot = definitionSnapshot;
        this.executionPolicy = executionPolicy;
        this.input = input;
        this.triggerType = triggerType;
        this.status = status;
        this.stepsExecuted = stepsExecuted;
        this.nextStepSequence = nextStepSequence;
        this.aiUsageComplete = aiUsageComplete;
        this.createdAt = createdAt;
    }
    public static Run queuedSnapshot(String runId,String workflowId,String definition,String policy,String input,
            TriggerType trigger,String entry,Instant at) {
        var run=new Run(runId,workflowId,definition,policy,input,trigger,RunStatus.queued,0L,1L,true,at);
        run.currentNodeId=entry;
        run.aiTokensUsed=0L;
        return run;
    }
    public String getRunId() { return runId; }
    public String getWorkflowId() { return workflowId; }
    public String getDefinitionSnapshot() { return definitionSnapshot; }
    public String getExecutionPolicy() { return executionPolicy; }
    public String getInput() { return input; }
    public TriggerType getTriggerType() { return triggerType; }
    public RunStatus getStatus() { return status; }
    public String getCurrentNodeId() { return currentNodeId; }
    public Long getStepsExecuted() { return stepsExecuted; }
    public Long getNextStepSequence() { return nextStepSequence; }
    public Long getAiTokensUsed() { return aiTokensUsed; }
    public Boolean getAiUsageComplete() { return aiUsageComplete; }
    public String getError() { return error; }
    public Instant getCancelRequestedAt() { return cancelRequestedAt; }
    public String getCancelRequestedBy() { return cancelRequestedBy; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getRevision() { return revision; }
}
