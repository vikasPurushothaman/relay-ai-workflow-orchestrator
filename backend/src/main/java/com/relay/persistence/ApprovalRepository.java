package com.relay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Internal storage access; callers own authorization and transaction boundaries. */
public interface ApprovalRepository extends JpaRepository<Approval, String> {}
