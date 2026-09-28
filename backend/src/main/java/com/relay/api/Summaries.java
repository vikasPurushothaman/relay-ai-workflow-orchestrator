package com.relay.api;

import com.relay.persistence.*;
import java.time.Instant;

/** Minimal safe projections, not a promise of future list-route schemas. */
public final class Summaries {
    private Summaries() {}
    public record WorkflowSummary(String id, String name, WorkflowStatus status, Instant updatedAt) {}
    public record RunSummary(String runId, String workflowId, RunStatus status, Instant createdAt) {}
    public record ApprovalSummary(String id, String runId, Long stepSequence, ApprovalStatus status) {}
    public static WorkflowSummary workflow(Workflow value) {
        return new WorkflowSummary(value.getId(),value.getName(),value.getStatus(),value.getUpdatedAt());
    }
    public static RunSummary run(Run value) {
        return new RunSummary(value.getRunId(),value.getWorkflowId(),value.getStatus(),value.getCreatedAt());
    }
    public static ApprovalSummary approval(Approval value) {
        return new ApprovalSummary(value.getId(),value.getRunId(),value.getStepSequence(),value.getStatus());
    }
}
