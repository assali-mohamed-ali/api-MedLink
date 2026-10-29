package com.example.apimedlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * GitHub #49 (MLink-122): in staging and prod, Actuator lives on the private management port 9000. The profile
 * files, Prometheus and the full-stack compose file must agree on it: Prometheus scrapes that port over the
 * private network, and no compose file publishes it to the outside.
 */
class ManagementPortConfigTest {

    private static final int MANAGEMENT_PORT = 9000;

    @Test
    void stagingAndProdServeActuatorOnTheManagementPort() {
        for (String profile : List.of("staging", "prod")) {
            Map<String, Object> management = map(load("src/main/resources/application-" + profile + ".yml"), "management");

            assertThat(map(management, "server").get("port")).as(profile + " management.server.port").isEqualTo(MANAGEMENT_PORT);
        }
    }

    @Test
    void prometheusScrapesTheManagementPort() {
        List<Map<String, Object>> jobs = list(load("monitoring/prometheus/prometheus.yml"), "scrape_configs");

        assertThat(jobs).isNotEmpty();
        for (Map<String, Object> job : jobs) {
            assertThat(job.get("metrics_path")).isEqualTo("/actuator/prometheus");
            for (Map<String, Object> config : list(job, "static_configs")) {
                assertThat((List<?>) config.get("targets"))
                        .as("job %s", job.get("job_name"))
                        .allSatisfy(target -> assertThat(target.toString()).endsWith(":" + MANAGEMENT_PORT));
            }
        }
    }

    @Test
    void noComposeFilePublishesTheManagementPort() {
        for (String file : List.of("docker-compose.yml", "compose.yaml")) {
            Map<String, Object> services = map(load(file), "services");
            services.forEach((name, service) -> {
                Object ports = ((Map<?, ?>) service).get("ports");
                List<?> published = ports == null ? List.of() : (List<?>) ports;

                assertThat(published).as("%s %s ports", file, name)
                        .noneSatisfy(port -> assertThat(port.toString()).contains(String.valueOf(MANAGEMENT_PORT)));
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertThat(value).as("'%s' block", key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertThat(value).as("'%s' list", key).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> load(String file) {
        try (Reader reader = Files.newBufferedReader(Path.of(file))) {
            return new Yaml().load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }
}
