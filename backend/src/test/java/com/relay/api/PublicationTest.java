package com.relay.api;

import com.relay.persistence.*;
import com.relay.workflow.*;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicationTest {
    final Instant time=Instant.parse("2026-09-27T00:00:00Z");
    final String definition="""
        {"id":"wf","name":"Test","trigger":{"type":"manual"},"entry":"a","limits":{"max_steps":1},"nodes":[{"id":"a","type":"delay","params":{"seconds":0},"next":null}]}
        """;
    @Test void validatedRevisionIsTheOnlyRevisionFrozen() {
        var repository=mock(WorkflowRepository.class);
        var w=new Workflow("wf","Test",WorkflowStatus.draft,definition,time,time);
        ReflectionTestUtils.setField(w,"revision",2L);
        when(repository.lockById("wf")).thenReturn(Optional.of(w));
        var transactions=new PublicationTransactions(repository);
        assertThatThrownBy(()->transactions.freeze("wf",new PublicationTransactions.Draft(definition,1L)))
            .isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.reason()).isEqualTo(ApiFailure.Reason.CONFLICT));
        assertThat(w.getPublishedDefinition()).isNull();verify(repository,never()).flush();
        transactions.freeze("wf",new PublicationTransactions.Draft(definition,2L));
        assertThat(w.getPublishedDefinition()).isEqualTo(definition);
        var publishedAt=w.getPublishedAt();
        transactions.freeze("wf",new PublicationTransactions.Draft(definition,2L));
        assertThat(w.getPublishedAt()).isEqualTo(publishedAt);
        w.replaceDraft("Changed",null,definition.replace("Test","Changed"),time.plusSeconds(2));
        assertThat(w.getStatus()).isEqualTo(WorkflowStatus.draft);
        assertThat(w.getPublishedDefinition()).isEqualTo(definition);
    }
    @Test void invalidDraftNeverReachesPublicationWrite() {
        var transactions=mock(PublicationTransactions.class);
        when(transactions.read("wf")).thenReturn(new PublicationTransactions.Draft(definition.replace("delay","teleport"),1L));
        var service=new PublicationService(transactions,new DefinitionParser(),new PublishValidator(new NodeCatalog(),new OutputSchemaValidator()));
        assertThatThrownBy(()->service.publish("wf")).isInstanceOf(DefinitionException.class);
        verify(transactions,never()).freeze(anyString(),any());
        assertThat(new PublicationTransactions.Draft("private-secret",1L).toString()).doesNotContain("private-secret");
    }
}
