package com.example.apimedlink.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.service.ConsultationReportService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Review on #123: a write whose body length is unknown is refused, whatever the protocol says. An HTTP/2 request
 * (as an HTTP/2 ingress could forward it) has no Transfer-Encoding and may have no Content-Length: the container
 * then answers -1, and the guard must refuse it rather than let Spring read an unbounded body. The real API speaks
 * HTTP/1.1 only, so this branch is proven here; ConsultationReportFunctionalTest proves the HTTP/1.1 ones over HTTP.
 */
class ReportWriteGuardTest {

    final UUID practitioner = UUID.randomUUID();
    final UUID patient = UUID.randomUUID();
    final ConsultationReportService reports = mock(ConsultationReportService.class);
    final CurrentUser currentUser = mock(CurrentUser.class);
    final ReportWriteGuard guard = new ReportWriteGuard(reports, currentUser);

    @Test
    void anHttp2WriteWithNeitherContentLengthNorTransferEncodingIsRefusedWith411() {
        MockHttpServletRequest request = write("HTTP/2.0");

        assertThatThrownBy(() -> guard.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.getStatus()).isEqualTo(HttpStatus.LENGTH_REQUIRED);
                    assertThat(refused.getCode()).isEqualTo("LENGTH_REQUIRED");
                });
    }

    @Test
    void anHttp11WriteWithNeitherHeaderIsRefusedWith411Too() {
        MockHttpServletRequest request = write("HTTP/1.1");

        assertThatThrownBy(() -> guard.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.LENGTH_REQUIRED));
    }

    @Test
    void aWriteThatStatesALengthWithinTheLimitGoesThrough() {
        MockHttpServletRequest request = write("HTTP/2.0");
        request.setContent("{\"content\": \"Compte-rendu.\"}".getBytes());

        assertThat(guard.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    private MockHttpServletRequest write(String protocol) {
        when(currentUser.id()).thenReturn(practitioner);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/patients/" + patient + "/reports");
        request.setProtocol(protocol);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", patient.toString()));
        return request;
    }
}
