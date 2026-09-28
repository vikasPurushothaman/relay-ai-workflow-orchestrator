package com.relay.api;

import com.relay.persistence.WorkflowRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate proxied transaction boundaries avoid retaining the validation read in the write persistence context. */
@Service
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class PublicationTransactions {
    private final WorkflowRepository repository;
    public PublicationTransactions(WorkflowRepository repository) { this.repository=repository; }
    public record Draft(String definition,Long revision) {
        @Override public String toString() { return "Draft[redacted]"; }
    }
    @Transactional(readOnly=true)
    public Draft read(String id) {
        var w=repository.findById(id).orElseThrow(()->new ApiFailure(ApiFailure.Reason.NOT_FOUND));
        return new Draft(w.getDraftDefinition(),w.getRevision());
    }
    @Transactional
    public WorkflowService.Detail freeze(String id,Draft validated) {
        var w=repository.lockById(id).orElseThrow(()->new ApiFailure(ApiFailure.Reason.NOT_FOUND));
        if(!Objects.equals(w.getRevision(),validated.revision()) || !w.getDraftDefinition().equals(validated.definition()))
            throw new ApiFailure(ApiFailure.Reason.CONFLICT);
        w.freezePublication(Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.flush();
        return WorkflowService.detail(w);
    }
}
