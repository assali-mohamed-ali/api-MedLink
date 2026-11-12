package com.example.apimedlink.config;

import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.security.JsonAccessDeniedHandler;
import com.example.apimedlink.security.JsonAuthenticationEntryPoint;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.security.RouteWords;
import com.example.apimedlink.security.SessionValidityFilter;
import com.example.apimedlink.service.AuditService;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * BFF security: Keycloak handles login and passwords, the backend keeps the tokens
 * server-side and the browser only gets a session cookie (+ CSRF cookie for the SPA).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            OAuth2AuthorizedClientManager authorizedClients,
            JsonMapper json,
            AuditService audit,
            RouteWords routeWords,
            SessionRevocationStore revocations,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) throws Exception {
        // Same cookie as csrf.spa() (readable by the SPA), plus SameSite and Secure like the session cookie (MLink-84);
        // when not forced Secure, it stays Secure on HTTPS requests (the repository's default)
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> {
            cookie.sameSite("Strict");
            if (secureCookies) {
                cookie.secure(true);
            }
        });
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .authorizeHttpRequests(auth -> auth
                        // sendError (CSRF refused, 415...) forwards to /error: keep that status, not a 401
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/scalar",
                                "/scalar/**")
                        .permitAll()
                        // Staging/prod: Actuator exists only on the private management port, where Prometheus
                        // scrapes without a login; dev serves it on the main port (GitHub #49)
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                        .permitAll()
                        // Probes on the main port: {"status":"UP"} only, for the deploy check and healthchecks
                        .requestMatchers(HttpMethod.GET, "/livez", "/readyz")
                        .permitAll()
                        // Public auth endpoints (plan.md §4); CSRF still applies to these POSTs
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/verify-email",
                                "/api/auth/verify-email/resend",
                                "/api/auth/password",
                                // Staff code step: the pending step login started is the guard (MLink-62/63)
                                "/api/auth/mfa/setup",
                                "/api/auth/mfa/verify")
                        .permitAll()
                        // Patients only, checked before the body is read: a body Spring cannot parse would otherwise
                        // give a non-patient the 400 of the parsing, not 403 (review on #87). The method keeps its
                        // @PreAuthorize as well.
                        .requestMatchers(HttpMethod.POST, "/api/appointments", "/api/appointments/**")
                        .hasRole(Roles.PATIENT)
                        // The role before anything reads the request: Spring converts query dates and the JSON body before
                        // @PreAuthorize, so without this rule a patient would get 400, not 403 (MLink-26, review on #116)
                        .requestMatchers("/api/practitioner/**")
                        .hasRole(Roles.PRACTITIONER)
                        // The role before Spring reads the request, so a patient never gets a 400 (MLink-31, as #116)
                        .requestMatchers(HttpMethod.GET, "/api/patients/*/record")
                        .hasRole(Roles.PRACTITIONER)
                        // Staff only, decided before any body is read: a patient sending a bad body gets 403, not 400
                        .requestMatchers("/api/admin/**")
                        .hasRole(Roles.ADMINISTRATIVE_STAFF)
                        .anyRequest()
                        .authenticated())
                // Every logged-in call checks Keycloak still accepts the account (GitHub #28)
                .addFilterBefore(new SessionValidityFilter(authorizedClients, json, revocations), AuthorizationFilter.class)
                // Not logged in (no session, unknown, ended or expired): 401 with our JSON error, never a redirect
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(json))
                        // Every refusal, before or inside a controller: 403 with our JSON error, audited (MLink-59)
                        .accessDeniedHandler(new JsonAccessDeniedHandler(json, audit, routeWords)));
        return http.build();
    }
}
