package com.relay.bootstrap;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseStartupConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DatabaseStartupConfiguration.class);

    @Test
    void apiRegistersOnlyMigrationStrategyAndMigratesOnce() {
        runner.withPropertyValues("relay.launch.mode=api").run(context -> {
            assertThat(context).hasSingleBean(FlywayMigrationStrategy.class);
            Flyway flyway = mock(Flyway.class);
            context.getBean(FlywayMigrationStrategy.class).migrate(flyway);
            verify(flyway).migrate(); verifyNoMoreInteractions(flyway);
        });
    }

    @Test
    void workerRegistersOnlyValidationStrategyAndNeverMutatesOnRepeatedStartup() {
        runner.withPropertyValues("relay.launch.mode=worker").run(context -> {
            assertThat(context).hasSingleBean(FlywayMigrationStrategy.class);
            Flyway flyway = validDatabase();
            var strategy = context.getBean(FlywayMigrationStrategy.class);
            strategy.migrate(flyway); strategy.migrate(flyway);
            verify(flyway, times(2)).validate(); verify(flyway, times(2)).info();
            verifyNoMoreInteractions(flyway);
        });
    }

    @Test
    void scaffoldDoesNotRegisterMigrationStrategy() {
        runner.run(context -> assertThat(context).doesNotHaveBean(FlywayMigrationStrategy.class));
    }

    private Flyway validDatabase() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.current()).thenReturn(mock(MigrationInfo.class));
        when(info.pending()).thenReturn(new MigrationInfo[0]);
        return flyway;
    }

    @Test
    void workerRejectsEmptyAndPendingSchemasWithoutMutation() {
        for (boolean empty : new boolean[]{true,false}) {
            Flyway flyway = validDatabase();
            if (empty) when(flyway.info().current()).thenReturn(null);
            else when(flyway.info().pending()).thenReturn(new MigrationInfo[]{mock(MigrationInfo.class)});
            assertThatThrownBy(() -> DatabaseStartupConfiguration.validateWorkerSchema(flyway))
                    .hasMessageContaining("start the matching API migrations first").hasNoCause();
            verify(flyway, never()).migrate(); verify(flyway, never()).repair();
            verify(flyway, never()).baseline(); verify(flyway, never()).clean();
        }
    }

    @Test
    void workerRejectsChecksumOrDatabaseFailuresWithoutLeakingDriverDetails() {
        Flyway flyway = mock(Flyway.class);
        doThrow(new IllegalStateException("password=private db-host=private")).when(flyway).validate();
        assertThatThrownBy(() -> DatabaseStartupConfiguration.validateWorkerSchema(flyway))
                .hasMessageNotContaining("private").hasNoCause();
        verify(flyway).validate(); verifyNoMoreInteractions(flyway);
    }

    @Test
    void apiFailsClosedWithoutExposingDriverDetails() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate()).thenThrow(new IllegalStateException("password=private"));
        assertThatThrownBy(() -> new DatabaseStartupConfiguration().apiMigrations().migrate(flyway))
                .hasMessage("API database migration failed; verify database access and migration history").hasNoCause();
        verify(flyway).migrate(); verifyNoMoreInteractions(flyway);
    }
}
