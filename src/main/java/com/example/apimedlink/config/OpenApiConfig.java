package com.example.apimedlink.config;

import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import java.util.EnumSet;
import java.util.Set;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger cannot discover the CSRF handshake on its own: every write needs the
 * {@code X-XSRF-TOKEN} header (value of the {@code XSRF-TOKEN} cookie set by
 * {@code GET /api/auth/csrf}), enforced by Spring Security, invisible to springdoc.
 * This declares that header on every write operation so "Try it out" shows a field
 * for it. Docs only; enforcement stays in {@link SecurityConfig}.
 */
@Configuration
public class OpenApiConfig {

    static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final Set<HttpMethod> WRITE_METHODS =
            EnumSet.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    @Bean
    GlobalOpenApiCustomizer csrfHeaderForWrites() {
        return api -> api.getPaths().values().forEach(item -> item.readOperationsMap()
                .forEach((method, operation) -> {
                    if (!WRITE_METHODS.contains(method)
                            || operation.getParameters() != null
                                    && operation.getParameters().stream()
                                            .anyMatch(p -> CSRF_HEADER.equals(p.getName()))) {
                        return;
                    }
                    operation.addParametersItem(new HeaderParameter()
                            .name(CSRF_HEADER)
                            .description("Value of the XSRF-TOKEN cookie set by GET /api/auth/csrf.")
                            .required(true)
                            .schema(new StringSchema()));
                }));
    }
}
