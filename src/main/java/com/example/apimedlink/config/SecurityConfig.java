package com.example.apimedlink.config;

import com.example.apimedlink.security.JsonAccessDeniedHandler;
import com.example.apimedlink.security.JsonAuthenticationEntryPoint;
import com.example.apimedlink.security.RouteWords;
import com.example.apimedlink.security.SessionValidityFilter;
import com.example.apimedlink.service.AuditService;
import jakarta.servlet.DispatcherType;
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
            RouteWords routeWords) throws Exception {
        // Same cookie as csrf.spa() (readable by the SPA), plus SameSite like the session cookie
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
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
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                        .permitAll()
                        // Public auth endpoints (plan.md §4); CSRF still applies to these POSTs
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/verify-email",
                                "/api/auth/verify-email/resend")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // Every logged-in call checks Keycloak still accepts the account (GitHub #28)
                .addFilterBefore(new SessionValidityFilter(authorizedClients, json), AuthorizationFilter.class)
                // Not logged in (no session, unknown, ended or expired): 401 with our JSON error, never a redirect
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint(json))
                        // Every refusal, before or inside a controller: 403 with our JSON error, audited (MLink-59)
                        .accessDeniedHandler(new JsonAccessDeniedHandler(json, audit, routeWords)));
        return http.build();
    }
}
