package com.example.apimedlink.security;

import com.example.apimedlink.config.RateLimitProperties;
import com.example.apimedlink.config.RateLimitProperties.Rule;
import com.example.apimedlink.exception.ErrorBody;
import com.example.apimedlink.repository.RateLimitStore;
import com.example.apimedlink.repository.RateLimitStore.Hit;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-IP limits on the public auth endpoints (GitHub #51, plan.md §4): past the limit, 429
 * {@code TOO_MANY_REQUESTS} with {@code Retry-After}, before any validation, Keycloak call or mail.
 * The count is keyed by the IP only, so the answer is the same whatever email the body holds.
 *
 * <p>The IP is the request's remote address: behind a reverse proxy, Tomcat takes it from
 * {@code X-Forwarded-For} only when the proxy is trusted ({@code server.tomcat.remoteip.internal-proxies}).
 * Runs after Spring Security: a write without the CSRF header is refused there and not counted.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String CODE = "TOO_MANY_REQUESTS";
    public static final String MESSAGE = "Trop de demandes depuis votre connexion. Réessayez dans quelques minutes.";

    /**
     * A limited path, and the name its counter is kept under. The stored key is the name, a colon and a 64-character
     * hash, in a VARCHAR(100) column (V8): a name is at most 35 characters, so a long path gets a short name.
     */
    private record Limit(String counter, Rule rule) {}

    private final RateLimitStore store;
    private final Map<String, Limit> rules;
    private final JsonMapper json;

    public RateLimitFilter(RateLimitStore store, RateLimitProperties limits, JsonMapper json) {
        this.store = store;
        this.rules = Map.of(
                "/api/auth/login", new Limit("ip:/api/auth/login", limits.login()),
                "/api/auth/register", new Limit("ip:/api/auth/register", limits.register()),
                "/api/auth/verify-email/resend", new Limit("ip:/api/auth/verify-email/resend", limits.resend()),
                "/api/auth/account/activate/resend", new Limit("ip:/api/auth/activation/resend", limits.resend()));
        this.json = json;
    }

    /** The POST paths this filter limits; {@code OpenApiConfig} documents their 429 from this list. */
    public Set<String> limitedPaths() {
        return rules.keySet();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !rules.containsKey(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Limit limit = rules.get(request.getServletPath());
        Rule rule = limit.rule();
        Hit hit = store.hit(limit.counter(), request.getRemoteAddr(), rule.window());
        if (hit.count() <= rule.max()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(hit.secondsLeft()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(
                ErrorBody.of(HttpStatus.TOO_MANY_REQUESTS, CODE, MESSAGE, null)));
    }
}
