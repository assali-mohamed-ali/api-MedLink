package com.example.apimedlink.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Optional;

/** A step between the password and the session. Stub: the tests in PendingStepTest come first. */
public abstract class PendingStep<T> {

    protected PendingStep(Clock clock) {
    }

    public void start(T value, HttpServletRequest request) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public Optional<T> current(HttpServletRequest request) {
        throw new UnsupportedOperationException("MLink-62");
    }

    public void clear(HttpServletRequest request) {
        throw new UnsupportedOperationException("MLink-62");
    }
}
