package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * MLink-32 (32.2): the key that seals the consultation reports comes from {@code app.report.encryption-key}
 * ({@code APP_REPORT_ENCRYPTION_KEY}), never the staff code key. Without a valid one the application does not
 * start: a wrong key would seal reports nobody can read back.
 */
class ReportKeyStartupTest {

    /** 32 bytes, base64: a test value only, never a real key. */
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    final ApplicationContextRunner app = new ApplicationContextRunner().withUserConfiguration(ReportCipher.class);

    @Test
    void aValidKeyGivesTheCipher() {
        app.withPropertyValues("app.report.encryption-key=" + KEY)
                .run(context -> assertThat(context).hasSingleBean(ReportCipher.class));
    }

    @Test
    void noKeyStopsTheStartAndNamesTheVariable() {
        app.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("APP_REPORT_ENCRYPTION_KEY");
        });
    }

    @Test
    void aKeyOfTheWrongSizeStopsTheStartWithoutEchoingIt() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        app.withPropertyValues("app.report.encryption-key=" + tooShort).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().message().doesNotContain(tooShort);
        });
    }
}
