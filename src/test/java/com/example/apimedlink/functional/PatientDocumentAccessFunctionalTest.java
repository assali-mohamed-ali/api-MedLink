package com.example.apimedlink.functional;

import static com.example.apimedlink.functional.DocumentTestFiles.PDF;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MLink-123/125: the document endpoints' access rules, proven with real logins and real multipart
 * requests. Upload is staff-only. List is staff or the owner. Download is staff or the owner, and a
 * patient asking for someone else's document gets 404 (not 403) so ids cannot be probed.
 */
class PatientDocumentAccessFunctionalTest extends PatientDocumentFunctionalTestBase {

    @Test
    void anAnonymousCallerGets401OnUploadListAndDownload() throws Exception {
        UUID patientId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Browser anonymous = new Browser();

        assertThat(anonymous.postMultipart(documentsPath(patientId), "Titre",
                "a.pdf", "application/pdf", PDF).statusCode()).isEqualTo(401);
        assertThat(anonymous.call(documentsPath(patientId)).statusCode()).isEqualTo(401);
        assertThat(anonymous.getBytes(documentPath(patientId, documentId)).statusCode()).isEqualTo(401);
    }

    @Test
    void aPatientCannotUpload() throws Exception {
        TestPatient patient = newPatient("access-patient-upload");

        assertThat(patient.browser().postMultipart(documentsPath(patient.id()), "Titre",
                "a.pdf", "application/pdf", PDF).statusCode()).isEqualTo(403);
    }

    @Test
    void aPractitionerCannotUpload() throws Exception {
        TestPatient patient = newPatient("access-practitioner-upload");
        Browser practitioner = practitioner();

        assertThat(practitioner.postMultipart(documentsPath(patient.id()), "Titre",
                "a.pdf", "application/pdf", PDF).statusCode()).isEqualTo(403);
    }

    @Test
    void staffCanUpload() throws Exception {
        TestPatient patient = newPatient("access-staff-upload");
        Browser staff = staff();

        assertThat(staff.postMultipart(documentsPath(patient.id()), "Titre",
                "a.pdf", "application/pdf", PDF).statusCode()).isEqualTo(201);
    }

    @Test
    void theOwnerCanListAndDownload() throws Exception {
        TestPatient patient = newPatient("access-owner");
        Browser staff = staff();
        Map<?, ?> dto = upload(staff, patient.id(), "Compte rendu", "compte-rendu.pdf",
                "application/pdf", PDF);

        assertThat(patient.browser().call(documentsPath(patient.id())).statusCode()).isEqualTo(200);
        HttpResponse<byte[]> download = patient.browser()
                .getBytes(documentPath(patient.id(), dto.get("id")));
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(PDF);
    }

    @Test
    void anotherPatientGets403OnTheListAnd404OnTheDownload() throws Exception {
        TestPatient owner = newPatient("access-owner-2");
        TestPatient other = newPatient("access-other");
        Browser staff = staff();
        Map<?, ?> dto = upload(staff, owner.id(), "Prive", "prive.pdf", "application/pdf", PDF);

        assertThat(other.browser().call(documentsPath(owner.id())).statusCode()).isEqualTo(403);
        assertThat(other.browser().getBytes(documentPath(owner.id(), dto.get("id"))).statusCode())
                .isEqualTo(404);
    }

    @Test
    void staffCanListAndDownloadAnyPatient() throws Exception {
        TestPatient patient = newPatient("access-staff-read");
        Browser staff = staff();
        Map<?, ?> dto = upload(staff, patient.id(), "Compte rendu", "compte-rendu.pdf",
                "application/pdf", PDF);

        assertThat(staff.call(documentsPath(patient.id())).statusCode()).isEqualTo(200);
        HttpResponse<byte[]> download = staff.getBytes(documentPath(patient.id(), dto.get("id")));
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(PDF);
    }

    @Test
    void aPractitionerCannotListOrDownload() throws Exception {
        TestPatient patient = newPatient("access-practitioner-read");
        Browser staff = staff();
        Browser practitioner = practitioner();
        Map<?, ?> dto = upload(staff, patient.id(), "Compte rendu", "compte-rendu.pdf",
                "application/pdf", PDF);

        assertThat(practitioner.call(documentsPath(patient.id())).statusCode()).isEqualTo(403);
        assertThat(practitioner.getBytes(documentPath(patient.id(), dto.get("id"))).statusCode())
                .isEqualTo(403);
    }
}
