package com.relay.persistence;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.io.Serializable;
import java.util.Objects;
@Embeddable
public class StepAttemptId implements Serializable {
    @NotNull @com.relay.api.OpaqueId
    @Column(name="`run_id`", nullable=false, length=128)
    private String runId;
    @NotNull @Positive
    @Column(name="`step_sequence`", nullable=false)
    private Long stepSequence;
    @NotNull @Positive
    @Column(name="`attempt_no`", nullable=false)
    private Long attemptNo;
    protected StepAttemptId() {}
    public StepAttemptId(String runId, Long stepSequence, Long attemptNo) {
        this.runId = runId;
        this.stepSequence = stepSequence;
        this.attemptNo = attemptNo;
    }
    public String getRunId() { return runId; }
    public Long getStepSequence() { return stepSequence; }
    public Long getAttemptNo() { return attemptNo; }
    @Override public boolean equals(Object value) { return value instanceof StepAttemptId that && Objects.equals(runId, that.runId) && Objects.equals(stepSequence, that.stepSequence) && Objects.equals(attemptNo, that.attemptNo); }
    @Override public int hashCode() { return Objects.hash(runId, stepSequence, attemptNo); }
}
