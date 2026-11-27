package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.exception.ApiException;
import org.junit.jupiter.api.Test;

/** US-04: the consent rule without Spring, in both settings of {@code app.consent.required}. */
class ConsentPolicyTest {

    @Test
    void whenRequiredAMissingConsentIsRefusedNamingTheField() {
        ConsentPolicy policy = new ConsentPolicy(true);

        assertThatThrownBy(() -> policy.check(null)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getDetails()).containsKey("consentVersion"));
    }

    @Test
    void whenNotRequiredAMissingConsentPasses() {
        assertThatCode(() -> new ConsentPolicy(false).check(null)).doesNotThrowAnyException();
    }

    @Test
    void theCurrentVersionAlwaysPassesAndAnyOtherIsRefused() {
        for (boolean required : new boolean[] {true, false}) {
            ConsentPolicy policy = new ConsentPolicy(required);
            assertThatCode(() -> policy.check(ConsentPolicy.CURRENT_VERSION)).doesNotThrowAnyException();
            assertThatThrownBy(() -> policy.check("2020-01-v0")).isInstanceOf(ApiException.class);
        }
    }
}
