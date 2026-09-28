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
@Table(name="workflows")
@JsonIgnoreType
public class Workflow {
    @Id
    @NotNull
    @com.relay.api.OpaqueId
    @Column(name="`id`", nullable=false, columnDefinition="VARCHAR(128)", updatable=false)
    private String id;
    @NotNull
    @Column(name="`name`", nullable=false, columnDefinition="TEXT")
    private String name;
    @Column(name="`description`", nullable=true, columnDefinition="TEXT")
    private String description;
    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name="`status`", nullable=false, columnDefinition="VARCHAR(32)")
    private WorkflowStatus status;
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`draft_definition`", nullable=false, columnDefinition="JSON")
    private String draftDefinition;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="`published_definition`", nullable=true, columnDefinition="JSON")
    private String publishedDefinition;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`published_at`", nullable=true, columnDefinition="DATETIME(6)")
    private Instant publishedAt;
    @Version
    @PositiveOrZero
    @Column(name="`revision`", nullable=false, columnDefinition="BIGINT")
    private Long revision;
    @NotNull
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`created_at`", nullable=false, columnDefinition="DATETIME(6)")
    private Instant createdAt;
    @NotNull
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name="`updated_at`", nullable=false, columnDefinition="DATETIME(6)")
    private Instant updatedAt;
    protected Workflow() {}
    public Workflow(String id, String name, WorkflowStatus status, String draftDefinition, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.draftDefinition = draftDefinition;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    public void replaceDraft(String name, String description, String definition, Instant at) {
        this.name=name;
        this.description=description;
        this.draftDefinition=definition;
        this.status=WorkflowStatus.draft;
        this.updatedAt=at;
    }
    /** Called only after validation and revision checking under the workflow row lock. */
    public void freezePublication(Instant at) {
        if(status==WorkflowStatus.published && draftDefinition.equals(publishedDefinition)) return;
        this.publishedDefinition=draftDefinition;
        this.publishedAt=at;
        this.updatedAt=at;
        this.status=WorkflowStatus.published;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public WorkflowStatus getStatus() { return status; }
    public String getDraftDefinition() { return draftDefinition; }
    public String getPublishedDefinition() { return publishedDefinition; }
    public Instant getPublishedAt() { return publishedAt; }
    public Long getRevision() { return revision; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
