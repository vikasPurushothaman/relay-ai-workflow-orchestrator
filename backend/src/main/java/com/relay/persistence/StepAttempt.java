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
@Table(name="step_attempts")
@JsonIgnoreType
public class StepAttempt {
    @EmbeddedId @NotNull @Valid
    private StepAttemptId id;
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`status`", nullable=false, columnDefinition="VARCHAR(32)")
    private AttemptStatus status;
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`cause`", nullable=false, columnDefinition="VARCHAR(32)")
    private AttemptCause cause;
    @NotNull
    @PositiveOrZero
    @Column(name="`claim_generation`", nullable=false, columnDefinition="BIGINT")
    private Long claimGeneration;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`request`", nullable=true, columnDefinition="JSON")
    private String request;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`output`", nullable=true, columnDefinition="JSON")
    private String output;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`error`", nullable=true, columnDefinition="JSON")
    private String error;
    @Column(name="`provider`", nullable=true, columnDefinition="TEXT")
    private String provider;
    @Column(name="`model`", nullable=true, columnDefinition="TEXT")
    private String model;
    @PositiveOrZero
    @Column(name="`tokens_prompt`", nullable=true, columnDefinition="BIGINT")
    private Long tokensPrompt;
    @PositiveOrZero
    @Column(name="`tokens_completion`", nullable=true, columnDefinition="BIGINT")
    private Long tokensCompletion;
    @NotNull
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`started_at`", nullable=false, columnDefinition="DATETIME(6)")
    private Instant startedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`finished_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant finishedAt;
    @PositiveOrZero
    @Column(name="`duration_ms`", nullable=true, columnDefinition="BIGINT")
    private Long durationMs;
    protected StepAttempt() {}
    public StepAttempt(StepAttemptId id, AttemptStatus status, AttemptCause cause, Long claimGeneration, Instant startedAt) {
        this.id = id;
        this.status = status;
        this.cause = cause;
        this.claimGeneration = claimGeneration;
        this.startedAt = startedAt;
    }
    public StepAttemptId getId() { return id; }
    public AttemptStatus getStatus() { return status; }
    public AttemptCause getCause() { return cause; }
    public Long getClaimGeneration() { return claimGeneration; }
    public String getRequest() { return request; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public Long getTokensPrompt() { return tokensPrompt; }
    public Long getTokensCompletion() { return tokensCompletion; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getDurationMs() { return durationMs; }
}
