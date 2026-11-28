package com.example.apimedlink.controller;

import com.example.apimedlink.service.ConsentPolicy;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-04: the consent text and its version, public: the sign-up page shows it before the visitor has an account. */
@RestController
@RequestMapping("/api/auth/consent")
public class ConsentController {

    /** What the sign-up page shows; the visitor sends back {@code version} as {@code consentVersion}. */
    public record ConsentDto(String version, String text, boolean required) {}

    private final ConsentPolicy policy;

    public ConsentController(ConsentPolicy policy) {
        this.policy = policy;
    }

    @Operation(summary = "The current consent text and its version")
    @GetMapping
    public ConsentDto current() {
        policy.requireAvailable();
        return new ConsentDto(ConsentPolicy.CURRENT_VERSION, ConsentPolicy.TEXT, policy.required());
    }
}
