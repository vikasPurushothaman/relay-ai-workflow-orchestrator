package com.relay.bootstrap;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/** Public health plus stateless management authentication in API mode; scaffold stays health-only. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HealthSecurityConfiguration {
    @Bean
    SecurityFilterChain healthSecurityFilterChain(HttpSecurity http, org.springframework.core.env.Environment environment) throws Exception {
        boolean api = "api".equals(environment.getProperty("relay.launch.mode"));
        AuthenticationEntryPoint denied = (request, response, exception) ->
                { if (api) com.relay.security.ManagementTokenFilter.deny(response,401);
                  else response.setStatus(HttpStatus.UNAUTHORIZED.value()); };
        if (api) http.addFilterAt(com.relay.security.ManagementCors.filter(
                environment.getProperty("RELAY_ALLOWED_ORIGINS", "http://localhost:5173")), org.springframework.web.filter.CorsFilter.class);
        if (api) http.addFilterBefore(new com.relay.security.ManagementTokenFilter(environment.getRequiredProperty("RELAY_DEMO_TOKEN")),
                org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
        return http
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health",
                                "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers("/workflows", "/workflows/**", "/runs", "/runs/**", "/approvals", "/approvals/**")
                            .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                api && authentication.get().isAuthenticated() && authentication.get().getAuthorities().stream()
                                    .anyMatch(a -> com.relay.security.ManagementAuthentication.AUTHORITY.equals(a.getAuthority()))))
                        .requestMatchers(HttpMethod.POST,"/hooks/*")
                            .access((authentication,context) -> new org.springframework.security.authorization.AuthorizationDecision(api))
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(denied)
                        .accessDeniedHandler((request,response,exception) -> {
                            if (api) com.relay.security.ManagementTokenFilter.deny(response,403);
                            else response.setStatus(401);
                        }))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    UserDetailsService noScaffoldUsers() {
        // Prevent Boot's generated development password; no login mechanism exists yet.
        return username -> { throw new UsernameNotFoundException("Login is not available"); };
    }
}
