package com.example.apimedlink.config;

import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * GitHub #64 (MLink-164): staging and prod refuse to start without real database credentials. A missing
 * {@code SPRING_DATASOURCE_USERNAME} / {@code SPRING_DATASOURCE_PASSWORD}, or a value we know from the dev files
 * and the examples, stops the start; the message names the variable, never the value.
 *
 * <p>A {@link BeanFactoryPostProcessor} so that it runs before any bean is made, Flyway's included: nothing
 * connects to the database with guessable credentials. Needed although the profile files have no default:
 * {@code spring.datasource.*} is bound as configuration properties, and the binder keeps an unresolved
 * {@code ${...}} as plain text, so the start would only fail later, on the connection.
 *
 * <p>GitHub #57 (MLink-165): the same rules for Flyway's owner credentials ({@code SPRING_FLYWAY_USER} /
 * {@code SPRING_FLYWAY_PASSWORD}), and the API refuses to connect as that owner: it would keep every privilege
 * of the migrations, and the audit trail would lose its protection without anyone noticing.
 */
@Component
@Profile({"staging", "prod"})
public class DatabaseCredentialsCheck implements BeanFactoryPostProcessor {

    /** The old dev values ({@code application-dev.yml}) and the placeholders of the examples. */
    static final Set<String> PLACEHOLDERS = Set.of("myuser", "secret", "change-me", "change-me-prod");

    /**
     * Every placeholder of {@code .env*.example} and {@code terraform.tfvars*.example} starts with it
     * ({@code change-me-staging}, {@code change-me-prod-use-vault-or-gh-secrets}...), in any case.
     */
    static final String PLACEHOLDER_PREFIX = "change-me";

    /**
     * The fixed dev credentials of {@code compose.yaml} all start with it ({@code dev-only-app-role-password}...),
     * in any case: copied into a deployment environment, they are public values (code owner's review of #126).
     */
    static final String DEV_ONLY_PREFIX = "dev-only";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Environment environment = beanFactory.getBean(Environment.class);
        check(environment, "spring.datasource.username", "SPRING_DATASOURCE_USERNAME");
        check(environment, "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD");
        // GitHub #57 (MLink-165): Flyway migrates as the schema owner, the API connects as the runtime role
        check(environment, "spring.flyway.user", "SPRING_FLYWAY_USER");
        check(environment, "spring.flyway.password", "SPRING_FLYWAY_PASSWORD");
        if (environment.getProperty("spring.datasource.username").trim()
                .equalsIgnoreCase(environment.getProperty("spring.flyway.user").trim())) {
            throw new IllegalStateException("spring.datasource.username (SPRING_DATASOURCE_USERNAME) is the schema"
                    + " owner spring.flyway.user (SPRING_FLYWAY_USER): the API must connect as the runtime role,"
                    + " which cannot change the schema nor rewrite the audit trail (GitHub #57)");
        }
    }

    private static void check(Environment environment, String property, String variable) {
        if (isMissingOrPlaceholder(environment, property)) {
            throw new IllegalStateException(property + " (" + variable + ") is missing or a known placeholder:"
                    + " set the real value in the environment (GitHub #64)");
        }
    }

    private static boolean isMissingOrPlaceholder(Environment environment, String property) {
        String value;
        try {
            value = environment.getProperty(property);
        } catch (IllegalArgumentException unresolved) {
            // ${VARIABLE} with no such variable: the same as no value
            return true;
        }
        if (value == null || value.isBlank() || value.contains("${")) {
            return true;
        }
        String trimmed = value.trim();
        return PLACEHOLDERS.contains(trimmed) || startsWithIgnoreCase(trimmed, PLACEHOLDER_PREFIX)
                || startsWithIgnoreCase(trimmed, DEV_ONLY_PREFIX);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
