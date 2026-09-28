package com.relay.api;

import com.relay.workflow.SeedCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class SeedLoader {
    private final SeedCatalog catalog;
    private final SeedTransactions transactions;
    public SeedLoader(SeedCatalog catalog,SeedTransactions transactions) { this.catalog=catalog;this.transactions=transactions; }
    public record Result(int created,int preserved) {}
    public Result load() {
        var definitions=catalog.definitions(); // Validate the whole resource before any transaction writes.
        for(int attempt=0;attempt<3;attempt++) {
            try {
                int created=transactions.insertMissing(definitions);
                return new Result(created,definitions.size()-created);
            } catch(DataIntegrityViolationException | TransientDataAccessException e) {
                // Retry a NEW transaction: another API may have inserted one of our missing IDs.
            }
        }
        throw new IllegalStateException("Workflow seed loading failed after bounded database retries");
    }
}
