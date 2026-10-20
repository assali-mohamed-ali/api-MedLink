package com.example.apimedlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * GitHub #52: two compose files, one job each. compose.yaml holds the dev dependencies (fixed dev values,
 * the API runs on the host), docker-compose.yml the full stack (API image + Prometheus, secrets from .env).
 * Every service they share must be the same service: same image, command, container ports, mounts,
 * healthcheck, dependencies and environment. Only the secrets differ: docker-compose.yml requires them
 * ({@code ${X:?...}}), compose.yaml gives the dev value. Any other value is the default of the full stack.
 */
class ComposeFilesTest {

    private static final Set<String> FULL_STACK_ONLY = Set.of("app", "prometheus");

    // ${NAME:-default}, ${NAME:?message}, ${NAME}; $$ is a literal $ for the container.
    private static final Pattern VARIABLE = Pattern.compile("\\$\\$|\\$\\{(\\w+)(?::([-?])([^}]*))?}");

    private static final String REQUIRED = "<required>";

    private final Map<String, Map<String, Object>> dev = services("compose.yaml");
    private final Map<String, Map<String, Object>> full = services("docker-compose.yml");

    @Test
    void fullStackIsTheDevServicesPlusTheApiAndPrometheus() {
        Set<String> expected = new TreeSet<>(dev.keySet());
        expected.addAll(FULL_STACK_ONLY);

        assertThat(new TreeSet<>(full.keySet())).isEqualTo(expected);
    }

    @Test
    void sharedServicesUseTheSameImageCommandPortsMountsAndHealthcheck() {
        dev.forEach((name, devService) -> {
            Map<String, Object> fullService = full.get(name);
            for (String key : List.of("image", "command", "volumes", "healthcheck", "depends_on")) {
                assertThat(resolved(devService.get(key)))
                        .as("%s.%s", name, key)
                        .isEqualTo(resolved(fullService.get(key)));
            }
            assertThat(containerPorts(devService)).as("%s container ports", name).isEqualTo(containerPorts(fullService));
        });
    }

    @Test
    void sharedServicesHaveTheSameEnvironmentAndOnlySecretsDiffer() {
        dev.forEach((name, devService) -> {
            Map<String, String> devEnv = environment(devService);
            Map<String, String> fullEnv = environment(full.get(name));

            assertThat(devEnv.keySet()).as("%s environment keys", name).isEqualTo(fullEnv.keySet());
            fullEnv.forEach((key, value) -> {
                if (!value.contains(REQUIRED)) {
                    assertThat(devEnv.get(key)).as("%s %s", name, key).isEqualTo(value);
                }
            });
        });
    }

    @Test
    void bothFilesDeclareTheNamedVolumesTheirServicesUse() {
        assertThat(topLevelVolumes("compose.yaml")).isEqualTo(namedVolumes(dev));
        assertThat(topLevelVolumes("docker-compose.yml")).isEqualTo(namedVolumes(full));
    }

    @Test
    void springBootComposeIntegrationIsPinnedToTheDevFile() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
            properties.load(reader);
        }

        assertThat(properties.getProperty("spring.docker.compose.file")).isEqualTo("compose.yaml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String file) {
        try (Reader reader = Files.newBufferedReader(Path.of(file))) {
            return new Yaml().load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services(String file) {
        return (Map<String, Map<String, Object>>) load(file).get("services");
    }

    private static Set<String> topLevelVolumes(String file) {
        Object volumes = load(file).get("volumes");
        return volumes == null ? Set.of() : new TreeSet<>(((Map<?, ?>) volumes).keySet().stream().map(String::valueOf).toList());
    }

    // "name:/path" where name is not a host path (./, /, ~)
    private static Set<String> namedVolumes(Map<String, Map<String, Object>> services) {
        Set<String> names = new TreeSet<>();
        services.values().forEach(service -> {
            if (service.get("volumes") instanceof List<?> volumes) {
                volumes.stream().map(String::valueOf)
                        .map(volume -> volume.substring(0, volume.indexOf(':')))
                        .filter(source -> !source.startsWith(".") && !source.startsWith("/") && !source.startsWith("~"))
                        .forEach(names::add);
            }
        });
        return names;
    }

    // "5432", "8180:8080", "${HOST_PORT:-5432}:5432": the container side is after the last ':'
    private static Set<String> containerPorts(Map<String, Object> service) {
        Set<String> ports = new TreeSet<>();
        if (service.get("ports") instanceof List<?> list) {
            list.stream().map(port -> resolve(String.valueOf(port)))
                    .map(port -> port.substring(port.lastIndexOf(':') + 1))
                    .forEach(ports::add);
        }
        return ports;
    }

    // list form (KEY=value) or map form (KEY: value), values resolved
    private static Map<String, String> environment(Map<String, Object> service) {
        Map<String, String> env = new LinkedHashMap<>();
        Object raw = service.get("environment");
        if (raw instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(entry -> {
                int equals = entry.indexOf('=');
                env.put(entry.substring(0, equals), resolve(entry.substring(equals + 1)));
            });
        } else if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> env.put(String.valueOf(key), resolve(String.valueOf(value))));
        }
        return env;
    }

    private static Object resolved(Object value) {
        if (value instanceof String text) {
            return resolve(text);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(resolved(item)));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, resolved(item)));
            return copy;
        }
        return value;
    }

    // What compose would use with no .env: the default, or a marker for a required variable.
    private static String resolve(String text) {
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group().equals("$$")) {
                replacement = "$";
            } else if ("-".equals(matcher.group(2))) {
                replacement = matcher.group(3);
            } else {
                replacement = REQUIRED;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
