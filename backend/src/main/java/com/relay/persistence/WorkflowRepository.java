package com.relay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Internal storage access; callers own authorization and transaction boundaries. */
public interface WorkflowRepository extends JpaRepository<Workflow, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from Workflow w where w.id = :id")
    java.util.Optional<Workflow> lockById(@org.springframework.data.repository.query.Param("id") String id);
}
