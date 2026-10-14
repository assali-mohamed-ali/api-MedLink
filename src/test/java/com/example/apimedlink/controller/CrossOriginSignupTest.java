package com.example.apimedlink.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.service.KeycloakAdminService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * GitHub issue #31: the signup form runs on another origin (React, localhost:3000 in dev). The
 * configured origin passes CORS and CSRF; any other origin is refused before the controller runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CrossOriginSignupTest {

    static final String REACT = "http://localhost:3000";
    static final String OTHER = "http://evil.example";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @MockitoBean
    KeycloakAdminService keycloak;

    @MockitoBean
    JavaMailSender mailSender;

    String email;

    @BeforeEach
    void setUp() {
        UUID id = UUID.randomUUID();
        email = "cors-" + id + "@example.com";
        when(keycloak.createUser(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(id);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    private MockHttpServletRequestBuilder signupFrom(String origin) {
        return post("/api/auth/register")
                .header(HttpHeaders.ORIGIN, origin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"Sel-De-Mer-2026!","firstName":"Amel","lastName":"Ben Salah",
                         "birthDate":"1990-04-12"}
                        """.formatted(email));
    }

    @Test
    void signupFromTheConfiguredOriginSucceedsAndTheBrowserMayReadTheAnswer() throws Exception {
        mvc.perform(signupFrom(REACT).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, REACT))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

        assertThat(users.findByEmail(email)).isPresent();
    }

    @Test
    void theConfiguredOriginStillNeedsTheCsrfHeader() throws Exception {
        mvc.perform(signupFrom(REACT)).andExpect(status().isForbidden());

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void signupFromAnotherOriginIsRefusedEvenWithAValidCsrfToken() throws Exception {
        mvc.perform(signupFrom(OTHER).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void aPreflightFromAnotherOriginIsRefused() throws Exception {
        mvc.perform(options("/api/auth/register")
                        .header(HttpHeaders.ORIGIN, OTHER)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-xsrf-token"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
