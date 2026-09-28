package com.relay.api;

import com.relay.persistence.*;
import com.relay.workflow.WorkflowDefinition;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class SeedTransactions {
    private final WorkflowRepository repository;
    public SeedTransactions(WorkflowRepository repository) { this.repository=repository; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int insertMissing(List<WorkflowDefinition> definitions) {
        int created=0;
        for(var definition:definitions) {
            if(repository.existsById(definition.id())) continue;
            var tree=definition.json();
            var now=Instant.now().truncatedTo(ChronoUnit.MICROS);
            var w=new Workflow(definition.id(),tree.get("name").asString(),WorkflowStatus.draft,tree.toString(),now,now);
            w.replaceDraft(w.getName(),tree.has("description")?tree.get("description").asString():null,tree.toString(),now);
            w.freezePublication(now);
            repository.saveAndFlush(w); // Unique ID wins races; failure rolls back this entire batch.
            created++;
        }
        return created;
    }
}
