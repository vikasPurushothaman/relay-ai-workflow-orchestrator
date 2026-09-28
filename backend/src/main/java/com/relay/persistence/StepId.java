package com.relay.persistence;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.io.Serializable;
import java.util.Objects;
@Embeddable
public class StepId implements Serializable {
    @NotNull @com.relay.api.OpaqueId
    @Column(name="`run_id`", nullable=false, length=128)
    private String runId;
    @NotNull @Positive
    @Column(name="`sequence`", nullable=false)
    private Long sequence;
    protected StepId() {}
    public StepId(String runId, Long sequence) {
        this.runId = runId;
        this.sequence = sequence;
    }
    public String getRunId() { return runId; }
    public Long getSequence() { return sequence; }
    @Override public boolean equals(Object value) { return value instanceof StepId that && Objects.equals(runId, that.runId) && Objects.equals(sequence, that.sequence); }
    @Override public int hashCode() { return Objects.hash(runId, sequence); }
}
