package com.relay.persistence;

import com.relay.RelayApplication;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class PersistenceMySqlTest extends com.relay.testing.MySqlIntegrationSupport {
    @Test void realMysqlMapsEveryEntityAndPreservesConcurrencyAndNullSemantics() {
        assertThat(setting("RELAY_DB_URL")).as("Run scripts/check_persistence.py with disposable MySQL").isNotBlank();
        var app=new SpringApplication(RelayApplication.class);
        app.setRegisterShutdownHook(false);
        try (var context=start(app)) {
            var emf=context.getBean(EntityManagerFactory.class);
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var workflows=context.getBean(WorkflowRepository.class);
            var runs=context.getBean(RunRepository.class);
            var steps=context.getBean(StepRepository.class);
            var attempts=context.getBean(StepAttemptRepository.class);
            var approvals=context.getBean(ApprovalRepository.class);
            var jobs=context.getBean(QueueJobRepository.class);
            var time=Instant.parse("2026-09-25T10:12:13.123456Z");
            tx.executeWithoutResult(status -> {
                workflows.saveAndFlush(new Workflow("wf","Title",WorkflowStatus.draft,"{\"secret\":\"test-only\",\"unicode\":\"😀\"}",time,time));
                runs.saveAndFlush(new Run("r","wf","{\"name\":\"snapshot\"}","{\"policy_version\":1}","null",TriggerType.manual,RunStatus.queued,0L,1L,true,time));
                steps.saveAndFlush(new Step(new StepId("r",1L),"loop","notify",StepStatus.running,0L,0L,true));
                steps.saveAndFlush(new Step(new StepId("r",2L),"loop","notify",StepStatus.running,0L,0L,true));
                attempts.saveAndFlush(new StepAttempt(new StepAttemptId("r",1L,1L),AttemptStatus.running,AttemptCause.initial,1L,time));
                approvals.saveAndFlush(new Approval("a","r",1L,"loop","Review",ApprovalStatus.pending,time));
                jobs.saveAndFlush(new QueueJob("r","loop",JobStatus.ready,time,0L,0L,time,time));
            });
            assertThat(workflows.findById("wf").orElseThrow().getDraftDefinition()).contains("test-only","😀");
            assertThat(workflows.findById("wf").orElseThrow().getCreatedAt()).isEqualTo(time);
            assertThat(workflows.findById("missing")).isEmpty();
            try (var em=emf.createEntityManager()) {
                assertThat(em.createNativeQuery("SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f') FROM workflows WHERE id='wf'",String.class).getSingleResult())
                    .isEqualTo("2026-09-25 10:12:13.123456");
            }
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> runs.saveAndFlush(
                new Run("orphan","missing","{}","{}","{}",TriggerType.manual,RunStatus.queued,0L,1L,true,time))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(runs.findById("orphan")).isEmpty();
            assertThat(runs.findById("r").orElseThrow().getInput()).isEqualTo("null");
            assertThat(runs.findById("r").orElseThrow().getAiTokensUsed()).isNull();
            assertThat(steps.findById(new StepId("r",1L)).orElseThrow().getOutput()).isNull();
            assertThat(steps.findById(new StepId("r",2L))).isPresent();
            assertThat(attempts.findById(new StepAttemptId("r",1L,1L)).orElseThrow().getCause()).isEqualTo(AttemptCause.initial);
            assertThat(approvals.findById("a").orElseThrow().getDecidedAt()).isNull();
            assertThat(jobs.findById("r").orElseThrow().getStepSequence()).isNull();
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated("demo-operator",null,
                    java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("RELAY_MANAGEMENT"))));
            try {
                tx.executeWithoutResult(status -> approvals.findById("a").orElseThrow().recordHumanDecision(true,time));
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            assertThat(approvals.findById("a").orElseThrow().getDecidedBy()).isEqualTo("demo-operator");
            assertThat(approvals.findById("a").orElseThrow().getDecidedAt()).isEqualTo(time);
            try (var a=emf.createEntityManager();var b=emf.createEntityManager()) {
                a.getTransaction().begin(); b.getTransaction().begin();
                var first=a.find(Workflow.class,"wf"); var stale=b.find(Workflow.class,"wf");
                ReflectionTestUtils.setField(first,"name","Updated");
                a.getTransaction().commit();
                ReflectionTestUtils.setField(stale,"name","Lost update");
                assertThatThrownBy(b::flush).isInstanceOf(OptimisticLockException.class);
                b.getTransaction().rollback();
            }
            assertThat(workflows.findById("wf").orElseThrow().getName()).isEqualTo("Updated");
            assertThat(workflows.findById("wf").orElseThrow().getRevision()).isEqualTo(1L);
            try (var a=emf.createEntityManager();var b=emf.createEntityManager()) {
                a.getTransaction().begin();b.getTransaction().begin();
                var first=a.find(Run.class,"r");var stale=b.find(Run.class,"r");
                ReflectionTestUtils.setField(first,"stepsExecuted",1L);a.getTransaction().commit();
                ReflectionTestUtils.setField(stale,"stepsExecuted",2L);
                assertThatThrownBy(b::flush).isInstanceOf(OptimisticLockException.class);
                b.getTransaction().rollback();
            }
            assertThat(runs.findById("r").orElseThrow().getStepsExecuted()).isEqualTo(1L);
            tx.executeWithoutResult(status -> {
                workflows.saveAndFlush(new Workflow("rollback","Temporary",WorkflowStatus.draft,"{}",time,time));
                status.setRollbackOnly();
            });
            assertThat(workflows.findById("rollback")).isEmpty();
        }
    }
}
