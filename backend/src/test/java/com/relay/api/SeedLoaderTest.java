package com.relay.api;

import com.relay.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeedLoaderTest {
    @Test void retriesWithBoundAndPreservationCounts() {
        var catalog=new SeedCatalog(new DefinitionParser(),new PublishValidator(new NodeCatalog(),new OutputSchemaValidator()));
        var tx=mock(SeedTransactions.class);
        when(tx.insertMissing(anyList())).thenThrow(new DataIntegrityViolationException("private-sentinel")).thenReturn(2);
        assertThat(new SeedLoader(catalog,tx).load()).isEqualTo(new SeedLoader.Result(2,2));
        verify(tx,times(2)).insertMissing(anyList());
        reset(tx);when(tx.insertMissing(anyList())).thenThrow(new DataIntegrityViolationException("private-sentinel"));
        assertThatThrownBy(()->new SeedLoader(catalog,tx).load()).hasMessage("Workflow seed loading failed after bounded database retries").hasNoCause();
        verify(tx,times(3)).insertMissing(anyList());
    }
    @Test void invalidResourceCannotStartWrites() {
        var catalog=mock(SeedCatalog.class);var tx=mock(SeedTransactions.class);
        when(catalog.definitions()).thenThrow(new IllegalStateException("Invalid resource"));
        assertThatThrownBy(()->new SeedLoader(catalog,tx).load()).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(tx);
    }
}
