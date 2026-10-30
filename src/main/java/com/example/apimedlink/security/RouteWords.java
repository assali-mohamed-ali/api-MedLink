package com.example.apimedlink.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The literal words of the API's own routes ({@code api}, {@code users}, {@code auth}, {@code login}...),
 * read once from Spring's request mappings. The audit trail keeps a path segment only when it is one of
 * these words or a UUID (review on PR #56): a new endpoint brings its words with it, nothing to maintain.
 */
@Component
public class RouteWords {

    private final Set<String> words;

    public RouteWords(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.words = mappings.getHandlerMethods().keySet().stream()
                .flatMap(route -> route.getPatternValues().stream())
                .flatMap(pattern -> Arrays.stream(pattern.split("/")))
                .filter(word -> !word.isEmpty() && !word.contains("{") && !word.contains("*"))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean contains(String segment) {
        return words.contains(segment);
    }
}
