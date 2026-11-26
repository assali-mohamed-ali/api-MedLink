package com.example.apimedlink.service;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MLink-72 (review of #122): takes again the account operations that were registered but not closed, because the
 * request that started them failed on an outage or the process stopped. Each one is carried out again (every step is
 * idempotent) and closed, with its closing audit entry, exactly once. See {@link AccountOperationRunner}.
 */
@Component
public class AccountOperationWorker {

    private final AccountOperationRunner runner;
    private final AdminUserService admin;

    public AccountOperationWorker(AccountOperationRunner runner, AdminUserService admin) {
        this.runner = runner;
        this.admin = admin;
    }

    @Scheduled(initialDelayString = "${app.account-operations.poll-interval:30s}",
            fixedDelayString = "${app.account-operations.poll-interval:30s}")
    public void poll() {
        runner.runDue(Instant::now, admin::execute);
    }

    /** The pending operations that are due at {@code now}; returns how many were taken (the tests choose the time). */
    public int runDue(Instant now) {
        return runner.runDue(now, admin::execute);
    }
}
