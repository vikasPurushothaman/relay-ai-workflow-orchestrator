package com.relay.api;

import com.relay.persistence.*;
import jakarta.validation.Validation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ApiFoundationTest {
    record Input(@NotNull @OpaqueId String id) {}
    @RestController static class Probe {
        @PostMapping("/probe") Input echo(@Valid @RequestBody Input input) { return input; }
        @GetMapping("/probe/{kind}") Object fail(@PathVariable("kind") String kind) {
            switch (kind) {
                case "missing": throw new ApiFailure(ApiFailure.Reason.NOT_FOUND);
                case "conflict": throw new ApiFailure(ApiFailure.Reason.CONFLICT);
                case "integrity": throw new DataIntegrityViolationException("private-sentinel SQL password");
                case "optimistic": throw new OptimisticLockingFailureException("private-sentinel");
                default: throw new IllegalStateException("private-sentinel stack");
            }
        }
    }
    @Test void unicodeIdentifiersAndCompositeIds() {
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            for (String id:new String[]{"a","A","😀".repeat(128),"x".repeat(128)})
                assertThat(validator.validate(new Input(id))).isEmpty();
            for (String id:new String[]{null,"","x".repeat(129),"😀".repeat(129),"\uD800"})
                assertThat(validator.validate(new Input(id))).isNotEmpty();
        }
        assertThat(new StepId("r",1L)).isEqualTo(new StepId("r",1L)).hasSameHashCodeAs(new StepId("r",1L));
        assertThat(new StepId("r",1L)).isNotEqualTo(new StepId("r",2L));
        assertThat(new StepAttemptId("r",1L,1L)).isNotEqualTo(new StepAttemptId("r",1L,2L));
    }
    @Test void dtoExcludesDefinitionsAndTokens() throws Exception {
        var now=Instant.parse("2026-09-25T00:00:00Z");
        var workflow=new Workflow("wf","Title",WorkflowStatus.draft,"{\"secret\":\"private-sentinel\"}",now,now);
        String json=new tools.jackson.databind.json.JsonMapper().writeValueAsString(Summaries.workflow(workflow));
        assertThat(json).contains("wf","Title","draft").doesNotContain("private-sentinel","definition","secret");
    }
    @Test void validAndInvalidBody() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ApiErrorAdvice()).build();
        mvc.perform(post("/probe").contentType("application/json").content("{\"id\":\"wf\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("wf"));
        for (String body:new String[]{"{","{}","{\"id\":\"\"}"})
            mvc.perform(post("/probe").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_input"));
    }
    @ParameterizedTest @ValueSource(strings={"missing","conflict","integrity","optimistic","unexpected"})
    void errorsAreConsistentAndSafe(String kind) throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ApiErrorAdvice()).build();
        int status=kind.equals("missing")?404:kind.equals("unexpected")?500:409;
        var result=mvc.perform(get("/probe/"+kind)).andExpect(status().is(status))
            .andExpect(jsonPath("$.error.message").isString()).andExpect(jsonPath("$.error.code").isString()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-sentinel","SQL","password","stack");
    }
}
