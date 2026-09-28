package com.relay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Internal storage access; callers own authorization and transaction boundaries. */
public interface StepRepository extends JpaRepository<Step, StepId> {}
