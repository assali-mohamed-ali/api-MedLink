package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * MLink-60 (story MLink-16): who may call what. One line per /api route; a route without a line
 * fails the build, so nobody adds an endpoint and forgets its access rule.
 */
class AccessMatrixFunctionalTest extends FunctionalTestBase {

    enum Expect { OPEN, LOGIN, FORBIDDEN } // OPEN: past security (not 401/403) · LOGIN: 401 · FORBIDDEN: 403

    record Rule(String method, String path, Expect anonymous, Expect patient, Expect practitioner, Expect staff) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static final Expect O = Expect.OPEN, L = Expect.LOGIN, F = Expect.FORBIDDEN;

    //                 method    path                                anonymous patient practitioner staff
    static final List<Rule> MATRIX = List.of();

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyApiRouteHasALineInTheMatrix() {
        Set<String> routes = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()
                        .filter(path -> path.startsWith("/api/"))
                        .flatMap(path -> info.getMethodsCondition().getMethods().isEmpty()
                                ? java.util.stream.Stream.of("ANY " + path) // no method given: must be listed too
                                : info.getMethodsCondition().getMethods().stream().map(method -> method.name() + " " + path)))
                .collect(Collectors.toSet());
        Set<String> inMatrix = MATRIX.stream().map(Rule::toString).collect(Collectors.toSet());

        assertThat(inMatrix).as("one matrix line per /api route, no more, no less").isEqualTo(routes);
    }
}