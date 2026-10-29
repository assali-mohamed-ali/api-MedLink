package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * MLink-62: the key that seals the staff code secrets comes from {@code app.mfa.encryption-key}
 * ({@code APP_MFA_ENCRYPTION_KEY}). Without a valid one the application does not start: no staff login could
 * be checked, and a wrong key would seal secrets nobody can read back.
 */
class MfaKeyStartupTest {

    /** 32 bytes, base64: a test value only, never a real key. */
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    final ApplicationContextRunner app = new ApplicationContextRunner().withUserConfiguration(MfaSecretCipher.class);

    @Test
    void aValidKeyGivesTheCipher() {
        app.withPropertyValues("app.mfa.encryption-key=" + KEY)
                .run(context -> assertThat(context).hasSingleBean(MfaSecretCipher.class));
    }

    @Test
    void noKeyStopsTheStartAndNamesTheVariable() {
        app.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("APP_MFA_ENCRYPTION_KEY");
        });
    }

    @Test
    void aKeyOfTheWrongSizeStopsTheStartWithoutEchoingIt() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        app.withPropertyValues("app.mfa.encryption-key=" + tooShort).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().message().doesNotContain(tooShort);
        });
    }
}
