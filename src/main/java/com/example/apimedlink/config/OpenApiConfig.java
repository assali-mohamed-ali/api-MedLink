package com.example.apimedlink.config;

import com.example.apimedlink.security.RateLimitFilter;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * What Spring Security and our filters enforce is invisible to springdoc, so it is declared here.
 * <ul>
 *   <li>Every write needs the {@code X-XSRF-TOKEN} header (value of the {@code XSRF-TOKEN} cookie set by
 *       {@code GET /api/auth/csrf}): declared on every write so "Try it out" shows a field for it.</li>
 *   <li>The per-IP limits of {@link RateLimitFilter} answer 429 with {@code Retry-After}: declared on
 *       exactly the paths the filter limits (GitHub #51).</li>
 * </ul>
 * Docs only; enforcement stays in {@link SecurityConfig} and {@link RateLimitFilter}.
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

    @Bean
    GlobalOpenApiCustomizer tooManyRequestsOnLimitedEndpoints(RateLimitFilter limits) {
        return api -> limits.limitedPaths().forEach(path -> {
            PathItem item = api.getPaths().get(path);
            if (item != null && item.getPost() != null) {
                item.getPost().getResponses().addApiResponse("429", tooManyRequests());
            }
        });
    }

    private static ApiResponse tooManyRequests() {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("timestamp", "2026-10-19T08:00:00Z");
        example.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        example.put("error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        example.put("code", RateLimitFilter.CODE);
        example.put("message", RateLimitFilter.MESSAGE);
        example.put("details", Map.of());
        return new ApiResponse()
                .description("Too many requests from this IP address in the current window. Same answer whatever"
                        + " the email in the body. Retry after the number of seconds in Retry-After.")
                .addHeaderObject(HttpHeaders.RETRY_AFTER, new Header()
                        .description("Seconds until the window ends.")
                        .schema(new IntegerSchema()))
                .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().schema(errorBody()).example(example)));
    }

    // The one error shape of the API (exception.ErrorBody).
    private static ObjectSchema errorBody() {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("timestamp", new DateTimeSchema());
        schema.addProperty("status", new IntegerSchema());
        schema.addProperty("error", new StringSchema());
        schema.addProperty("code", new StringSchema());
        schema.addProperty("message", new StringSchema());
        schema.addProperty("details", new MapSchema().additionalProperties(new StringSchema()));
        return schema;
    }
}
