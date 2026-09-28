package com.relay.workflow;

import java.io.InputStream;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class SeedCatalog {
    private final DefinitionParser parser;
    private final PublishValidator validator;
    public SeedCatalog(DefinitionParser parser,PublishValidator validator) { this.parser=parser;this.validator=validator; }
    public List<WorkflowDefinition> definitions() {
        try(var stream=SeedCatalog.class.getResourceAsStream("/catalog/seed_workflows.json")) {
            return read(stream);
        } catch(Exception e) { throw new IllegalStateException("Bundled workflow seeds are invalid or unavailable"); }
    }
    List<WorkflowDefinition> read(InputStream stream) {
        if(stream==null) throw new IllegalStateException("Missing seed resource");
        var root=JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder().maxNestingDepth(64).build()).build())
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readTree(stream);
        var seeds=root.get("workflows");
        if(seeds==null || !seeds.isArray() || seeds.size()!=4) throw new IllegalStateException("Expected four seed workflows");
        var result=new ArrayList<WorkflowDefinition>();var ids=new HashSet<String>();
        for(var seed:seeds) {
            var definition=parser.parse(seed.toString());
            validator.validate(definition);
            if(!ids.add(definition.id())) throw new IllegalStateException("Duplicate seed ID");
            result.add(definition);
        }
        result.sort(Comparator.comparing(WorkflowDefinition::id));
        return List.copyOf(result);
    }
}
