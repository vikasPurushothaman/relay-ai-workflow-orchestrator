package com.relay.api;

import com.relay.workflow.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="relay.launch.mode",havingValue="api")
public class PublicationService {
    private final PublicationTransactions transactions;
    private final DefinitionParser parser;
    private final PublishValidator validator;
    public PublicationService(PublicationTransactions transactions,DefinitionParser parser,PublishValidator validator) {
        this.transactions=transactions;this.parser=parser;this.validator=validator;
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public WorkflowService.Detail publish(String id) {
        var draft=transactions.read(id);
        var definition=parser.parse(draft.definition());
        if(!definition.id().equals(id)) throw new DefinitionException("immutable_id","$.id");
        validator.validate(definition);
        return transactions.freeze(id,draft);
    }
}
