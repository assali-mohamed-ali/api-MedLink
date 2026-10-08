package com.example.apimedlink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ActuatorIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthIsUp() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("UP");
    }
}
