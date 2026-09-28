package com.relay.security;

import com.relay.persistence.*;
import com.relay.api.ApiFailure;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class DecisionActorTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    static void authenticate(){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
        ManagementAuthentication.PRINCIPAL,null,List.of(new SimpleGrantedAuthority(ManagementAuthentication.AUTHORITY))));}
    @Test void anonymousAndUntrustedIdentityCannotDecide(){
        var a=new Approval("a","r",1L,"node","Review",ApprovalStatus.pending,Instant.EPOCH);
        assertThatThrownBy(() -> a.recordHumanDecision(true,Instant.now())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("forged",null,List.of(new SimpleGrantedAuthority(ManagementAuthentication.AUTHORITY))));
        assertThatThrownBy(ManagementAuthentication::requireActor).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(a.getStatus()).isEqualTo(ApprovalStatus.pending);
    }
    @Test void decisionIsStampedAndCannotBeReplaced(){
        authenticate();
        var a=new Approval("a","r",1L,"node","Review",ApprovalStatus.pending,Instant.EPOCH);
        assertThatThrownBy(() -> a.recordHumanDecision(true,null)).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> a.recordHumanDecision(true,Instant.EPOCH.minusSeconds(1))).isInstanceOf(ApiFailure.class);
        a.recordHumanDecision(false,Instant.EPOCH);
        assertThat(a.getDecidedBy()).isEqualTo("demo-operator");
        assertThat(a.getDecidedAt()).isEqualTo(Instant.EPOCH);
        assertThat(a.getStatus()).isEqualTo(ApprovalStatus.rejected);
        assertThatThrownBy(() -> a.recordHumanDecision(true,Instant.now())).isInstanceOf(ApiFailure.class);
        var closed=new Approval("c","r",2L,"node","Review",ApprovalStatus.closed,Instant.EPOCH);
        assertThatThrownBy(() -> closed.recordHumanDecision(true,Instant.now())).isInstanceOf(ApiFailure.class);
    }
}
