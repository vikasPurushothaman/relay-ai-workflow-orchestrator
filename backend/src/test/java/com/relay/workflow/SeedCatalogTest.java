package com.relay.workflow;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SeedCatalogTest {
    SeedCatalog catalog=new SeedCatalog(new DefinitionParser(),new PublishValidator(new NodeCatalog(),new OutputSchemaValidator()));
    @Test void exactOriginalSeedsAreValidatedAndOrdered() throws Exception {
        byte[] original=Files.readAllBytes(Path.of("../docs/source-review/pack/data/seed_workflows.json"));
        assertThat(Files.readAllBytes(Path.of("src/main/resources/catalog/seed_workflows.json"))).isEqualTo(original);
        var definitions=catalog.definitions();
        assertThat(definitions.stream().map(WorkflowDefinition::id)).containsExactly("wf_expense_approval","wf_runaway","wf_slow_fulfillment","wf_support_triage");
        var nodes=new JsonMapper().readTree(original).get("workflows");
        for(var definition:definitions) assertThat(java.util.stream.StreamSupport.stream(nodes.spliterator(),false)
            .anyMatch(node->node.equals(definition.json()))).isTrue();
        assertThatThrownBy(()->definitions.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void missingMalformedAndDuplicateSeedsReject() throws Exception {
        assertThatThrownBy(()->catalog.read(null)).isInstanceOf(IllegalStateException.class);
        String original=Files.readString(Path.of("src/main/resources/catalog/seed_workflows.json"));
        for(String bad:java.util.List.of("{}","{","{\"workflows\":[]}",original+" {}",
            original.replace("wf_support_triage","wf_runaway"),original.replace("\"type\": \"ai\"","\"type\": \"unknown\""),
            original.replace("\"name\": \"Support ticket triage\"","\"name\": \"A\",\"name\":\"B\""))) {
            assertThatThrownBy(()->catalog.read(new ByteArrayInputStream(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(RuntimeException.class);
        }
    }
}
