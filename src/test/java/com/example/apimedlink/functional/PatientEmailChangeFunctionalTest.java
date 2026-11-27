package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PatientEmailChangeFunctionalTest extends FunctionalTestBase {
    private static final String PASSWORD = "Email-Change-2026!";
    private static final String PATH = "/api/patients/me/email-change";
    private final KeycloakConsole console = new KeycloakConsole();
    @Autowired JdbcTemplate jdbc;

    @Test
    void verifiedNewEmailChangesLoginAndPreservesPatientIdentity() throws Exception {
        String old = uniqueEmail("email-change-old"), target = uniqueEmail("email-change-new");
        UUID id = console.createUser(old, PASSWORD);
        Browser patient = new Browser();
        assertThat(patient.apiLogin(old, PASSWORD).statusCode()).isEqualTo(200);
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Test', ?)",
                id, Date.valueOf("1990-04-12"));
        assertThat(patient.postJson(PATH, "{\"email\":\"" + target + "\",\"currentPassword\":\"" + PASSWORD + "\"}")
                .statusCode()).isEqualTo(204);
        Mailbox mailbox = new Mailbox();
        var mails = mailbox.to(target, "Confirmez votre nouvelle adresse MedLink");
        assertThat(mails).hasSize(1);
        var matcher = Pattern.compile("[A-Za-z0-9_-]{43}").matcher(mailbox.text(mails.get(0)));
        assertThat(matcher.find()).isTrue();
        String code = matcher.group();
        assertThat(jdbc.queryForObject("select token_hash from patient_email_changes where patient_id=?", String.class, id))
                .isNotEqualTo(code);
        assertThat(jdbc.queryForObject("select email from users where id=?", String.class, id)).isEqualTo(old);
        assertThat(patient.postJson(PATH + "/confirm", "{\"code\":\"" + code + "\"}").statusCode()).isEqualTo(202);
        assertThat(jdbc.queryForObject("select email from users where id=?", String.class, id)).isEqualTo(target);
        assertThat(jdbc.queryForObject("select username from users where id=?", String.class, id)).isEqualTo(target);
        assertThat(patient.call("/api/patients/me").statusCode()).isEqualTo(401);
        Browser again = new Browser();
        assertThat(again.apiLogin(target, PASSWORD).statusCode()).isEqualTo(200);
        assertThat(again.postJson(PATH + "/confirm", "{\"code\":\"" + code + "\"}").statusCode()).isEqualTo(400);
        assertThat(new Browser().apiLogin(old, PASSWORD).statusCode()).isNotEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from patients where id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from patient_email_changes where patient_id=?", Integer.class, id)).isZero();
    }
}
