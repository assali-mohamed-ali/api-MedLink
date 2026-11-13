package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MLink-32 (32.2): the consultation report content at rest, AES-256-GCM. The report id and the patient id are the
 * associated data: a sealed text copied to another report or another patient does not decrypt.
 */
class ReportCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString(filled(7));
    static final String CONTENT = "Consultation du 10/11 : tension 12/8, à revoir dans 3 mois. Allergie : pénicilline.";

    final ReportCipher cipher = new ReportCipher(KEY);
    final UUID report = UUID.randomUUID();
    final UUID patient = UUID.randomUUID();

    @Test
    void aReportReadsBackExactlyAsWrittenAccentsIncluded() {
        assertThat(cipher.decrypt(report, patient, cipher.encrypt(report, patient, CONTENT))).isEqualTo(CONTENT);
    }

    @Test
    void theStoredTextIsNotThePlainTextAndChangesEachTime() {
        String first = cipher.encrypt(report, patient, CONTENT);
        String second = cipher.encrypt(report, patient, CONTENT);

        assertThat(first).isNotEqualTo(second); // a new IV each time
        for (String stored : new String[] {first, second}) {
            assertThat(stored).doesNotContain("tension").doesNotContain("pénicilline");
            assertThat(new String(Base64.getDecoder().decode(stored))).doesNotContain("tension");
        }
    }

    @Test
    void aStoredTextCopiedToAnotherReportOrAnotherPatientDoesNotDecrypt() {
        String stored = cipher.encrypt(report, patient, CONTENT);

        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), patient, stored)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(report, UUID.randomUUID(), stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aChangedStoredTextOrAnotherKeyIsRefusedWithoutEchoingTheContent() {
        String stored = cipher.encrypt(report, patient, CONTENT);
        byte[] bytes = Base64.getDecoder().decode(stored);
        bytes[bytes.length - 1] ^= 1;
        String changed = Base64.getEncoder().encodeToString(bytes);
        ReportCipher otherKey = new ReportCipher(Base64.getEncoder().encodeToString(filled(9)));

        assertThatThrownBy(() -> cipher.decrypt(report, patient, changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("tension");
        assertThatThrownBy(() -> otherKey.decrypt(report, patient, stored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(KEY);
        assertThatThrownBy(() -> cipher.decrypt(report, patient, "not base64 !"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingOrWrongKeyIsRefusedAndNamesTheVariableWithoutEchoingTheKey() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new ReportCipher(""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("APP_REPORT_ENCRYPTION_KEY");
        assertThatThrownBy(() -> new ReportCipher("not base64 !"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("app.report.encryption-key");
        assertThatThrownBy(() -> new ReportCipher(tooShort))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(tooShort);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
