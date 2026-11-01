package com.example.apimedlink.functional;

import static com.example.apimedlink.functional.DocumentTestFiles.JPEG;
import static com.example.apimedlink.functional.DocumentTestFiles.PDF;
import static com.example.apimedlink.functional.DocumentTestFiles.PNG;
import static com.example.apimedlink.functional.DocumentTestFiles.TEN_MEBIBYTES;
import static com.example.apimedlink.functional.DocumentTestFiles.UNSUPPORTED;
import static com.example.apimedlink.functional.DocumentTestFiles.pdfOfSize;
import static com.example.apimedlink.functional.FunctionalTestBase.assertAttachmentContentDisposition;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MLink-123/125, through the real API, a real Keycloak, a real Postgres and a real MinIO: the patient
 * document lifecycle. A document is identified by its magic bytes, stored outside the database, encrypted
 * at rest, and read back byte for byte. The access matrix lives in {@link PatientDocumentAccessFunctionalTest}.
 */
class PatientDocumentFunctionalTest extends PatientDocumentFunctionalTestBase {

    @Test
    void anUploadAnswersCreatedWithALocationAndTheDocumentDto() throws Exception {
        TestPatient patient = newPatient("doc-upload");
        Browser staff = staff();

        HttpResponse<String> response = staff.postMultipart(documentsPath(patient.id()), "Compte rendu",
                "compte-rendu.pdf", "application/pdf", PDF);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        Map<?, ?> dto = Browser.json(response);
        assertThat(dto.get("id")).isNotNull();
        assertThat(dto.get("patientId")).isEqualTo(patient.id().toString());
        assertThat(dto.get("title")).isEqualTo("Compte rendu");
        assertThat(dto.get("originalFilename")).isEqualTo("compte-rendu.pdf");
        assertThat(dto.get("contentType")).isEqualTo("application/pdf");
        assertThat(((Number) dto.get("sizeBytes")).longValue()).isEqualTo(PDF.length);
        assertThat(dto.get("createdAt")).isNotNull();
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(location -> assertThat(location)
                .endsWith("/api/patients/" + patient.id() + "/documents/" + dto.get("id")));
    }

    @Test
    void pngAndJpegAreAcceptedByMagicBytesWhateverTheClientContentTypeSays() throws Exception {
        TestPatient patient = newPatient("doc-magic");
        Browser staff = staff();

        Map<?, ?> png = upload(staff, patient.id(), "Scan", "scan.txt", "text/plain", PNG);
        Map<?, ?> jpeg = upload(staff, patient.id(), "Photo", "photo.bin", "application/octet-stream", JPEG);

        assertThat(png.get("contentType")).as("PNG magic bytes win over the client type").isEqualTo("image/png");
        assertThat(jpeg.get("contentType")).as("JPEG magic bytes win over the client type").isEqualTo("image/jpeg");
        assertThat(png.get("originalFilename")).isEqualTo("scan.txt");
        assertThat(jpeg.get("originalFilename")).isEqualTo("photo.bin");
    }

    @Test
    void theTitleIsTrimmedAndAtMostTwoHundredCharacters() throws Exception {
        TestPatient patient = newPatient("doc-title");
        Browser staff = staff();

        Map<?, ?> trimmed = upload(staff, patient.id(), "  Compte rendu  ", "a.pdf", "application/pdf", PDF);
        Map<?, ?> longest = upload(staff, patient.id(), "x".repeat(200), "b.pdf", "application/pdf", PDF);

        assertThat(trimmed.get("title")).isEqualTo("Compte rendu");
        assertThat(((String) longest.get("title")).length()).isEqualTo(200);

        assertError(staff.postMultipart(documentsPath(patient.id()), "x".repeat(201),
                "c.pdf", "application/pdf", PDF), 400, "VALIDATION_FAILED");
    }

    @Test
    void aMaliciousFilenameIsStoredWithoutPathSegmentsControlsQuotesOrBackslashes() throws Exception {
        TestPatient patient = newPatient("doc-filename");
        Browser staff = staff();

        String stored = (String) upload(staff, patient.id(), "Titre",
                "../../etc/passwd\"quoted\\back.pdf", "application/pdf", PDF).get("originalFilename");

        assertThat(stored).isNotBlank().doesNotContain("/", "\\", "\"", "\r", "\n");
        assertThat(stored.length()).isLessThanOrEqualTo(255);
    }

    @Test
    void aVeryLongFilenameIsCappedAtTwoHundredAndFiftyFiveCharacters() throws Exception {
        TestPatient patient = newPatient("doc-long-name");
        Browser staff = staff();

        String stored = (String) upload(staff, patient.id(), "Titre",
                "a".repeat(300) + ".pdf", "application/pdf", PDF).get("originalFilename");

        assertThat(stored.length()).isLessThanOrEqualTo(255);
        assertThat(stored).isNotBlank();
    }

    @Test
    void aFilenameThatSanitizesToNothingFallsBackToDocument() throws Exception {
        TestPatient patient = newPatient("doc-fallback");
        Browser staff = staff();

        String stored = (String) upload(staff, patient.id(), "Titre",
                "..", "application/pdf", PDF).get("originalFilename");

        assertThat(stored).isEqualTo("document");
    }

    @Test
    void exactlyTenMebibytesIsAcceptedAndOneByteMoreIsRefused() throws Exception {
        TestPatient patient = newPatient("doc-size");
        Browser staff = staff();

        HttpResponse<String> accepted = staff.postMultipart(documentsPath(patient.id()), "Maximum",
                "maximum.pdf", "application/pdf", pdfOfSize(TEN_MEBIBYTES));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);

        assertError(staff.postMultipart(documentsPath(patient.id()), "Too big",
                "too-big.pdf", "application/pdf", pdfOfSize(TEN_MEBIBYTES + 1)), 413, "FILE_TOO_LARGE");
    }

    @Test
    void aBlankOrMissingTitleIsRefused() throws Exception {
        TestPatient patient = newPatient("doc-title-missing");
        Browser staff = staff();

        assertError(staff.postMultipart(documentsPath(patient.id()), "   ",
                "a.pdf", "application/pdf", PDF), 400, "VALIDATION_FAILED");
        assertError(staff.postMultipart(documentsPath(patient.id()), null,
                "b.pdf", "application/pdf", PDF), 400, "VALIDATION_FAILED");
    }

    @Test
    void anEmptyOrMissingFileIsRefused() throws Exception {
        TestPatient patient = newPatient("doc-file-missing");
        Browser staff = staff();

        assertError(staff.postMultipart(documentsPath(patient.id()), "Titre",
                "empty.pdf", "application/pdf", new byte[0]), 400, "VALIDATION_FAILED");
        assertError(staff.postMultipart(documentsPath(patient.id()), "Titre",
                null, null, null), 400, "VALIDATION_FAILED");
    }

    @Test
    void anUnsupportedMagicIsRefusedWithItsOwnCode() throws Exception {
        TestPatient patient = newPatient("doc-magic-unsupported");
        Browser staff = staff();

        assertError(staff.postMultipart(documentsPath(patient.id()), "Titre",
                "picture.gif", "image/gif", UNSUPPORTED), 400, "UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void aNonMultipartRequestIs415() throws Exception {
        TestPatient patient = newPatient("doc-media-type");
        Browser staff = staff();

        assertError(staff.post(documentsPath(patient.id()), "{}", true), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void anUnknownPatientIs404() throws Exception {
        Browser staff = staff();

        assertError(staff.postMultipart(documentsPath(UUID.randomUUID()), "Titre",
                "a.pdf", "application/pdf", PDF), 404, "NOT_FOUND");
    }

    @Test
    void aPatientAndAPractitionerCannotUpload() throws Exception {
        TestPatient patient = newPatient("doc-patient-upload");
        Browser practitioner = practitioner();

        assertThat(patient.browser().postMultipart(documentsPath(patient.id()), "Titre",
                "a.pdf", "application/pdf", PDF).statusCode()).isEqualTo(403);
        assertThat(practitioner.postMultipart(documentsPath(patient.id()), "Titre",
                "b.pdf", "application/pdf", PDF).statusCode()).isEqualTo(403);
    }

    @Test
    void anAnonymousCallerCannotUploadListOrDownload() throws Exception {
        UUID patientId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Browser anonymous = new Browser();

        assertThat(anonymous.postMultipart(documentsPath(patientId), "Titre",
                "a.pdf", "application/pdf", PDF).statusCode()).isEqualTo(401);
        assertThat(anonymous.call(documentsPath(patientId)).statusCode()).isEqualTo(401);
        assertThat(anonymous.getBytes(documentPath(patientId, documentId)).statusCode()).isEqualTo(401);
    }

    @Test
    void anEmptyListUsesTheDefaultPageAndAcceptsTheSizeBounds() throws Exception {
        TestPatient patient = newPatient("doc-list-empty");
        Browser staff = staff();

        HttpResponse<String> response = staff.call(documentsPath(patient.id()));

        assertThat(response.statusCode()).isEqualTo(200);
        Map<?, ?> page = Browser.json(response);
        assertThat((List<?>) page.get("items")).isEmpty();
        assertThat(((Number) page.get("page")).intValue()).isZero();
        assertThat(((Number) page.get("size")).intValue()).isEqualTo(20);
        assertThat(((Number) page.get("total")).longValue()).isZero();

        assertThat(staff.call(documentsPath(patient.id()) + "?size=1").statusCode()).isEqualTo(200);
        assertThat(staff.call(documentsPath(patient.id()) + "?size=50").statusCode()).isEqualTo(200);
    }

    @Test
    void theListPaginatesAndReturnsTheNewestDocumentFirst() throws Exception {
        TestPatient patient = newPatient("doc-list");
        Browser staff = staff();

        Map<?, ?> first = upload(staff, patient.id(), "Premier", "premier.pdf", "application/pdf", PDF);
        Thread.sleep(10);
        Map<?, ?> second = upload(staff, patient.id(), "Deuxieme", "deuxieme.pdf", "application/pdf", PDF);
        Thread.sleep(10);
        Map<?, ?> third = upload(staff, patient.id(), "Troisieme", "troisieme.pdf", "application/pdf", PDF);

        Map<?, ?> page0 = Browser.json(staff.call(documentsPath(patient.id()) + "?page=0&size=2"));
        Map<?, ?> page1 = Browser.json(staff.call(documentsPath(patient.id()) + "?page=1&size=2"));

        assertThat(((Number) page0.get("page")).intValue()).isZero();
        assertThat(((Number) page0.get("size")).intValue()).isEqualTo(2);
        assertThat(((Number) page0.get("total")).longValue()).isEqualTo(3);
        assertThat(ids(page0)).containsExactly(third.get("id"), second.get("id"));
        assertThat(ids(page1)).containsExactly(first.get("id"));
    }

    @Test
    void theListRejectsAnInvalidPageOrSize() throws Exception {
        TestPatient patient = newPatient("doc-list-invalid");
        Browser staff = staff();

        for (String query : List.of("?size=0", "?size=51", "?page=-1", "?size=abc")) {
            assertThat(staff.call(documentsPath(patient.id()) + query).statusCode())
                    .as("query " + query).isEqualTo(400);
        }
    }

    @Test
    void aDownloadReturnsTheExactBytesAndEverySecurityHeader() throws Exception {
        TestPatient patient = newPatient("doc-download");
        Browser staff = staff();
        Map<?, ?> dto = upload(staff, patient.id(), "Compte rendu", "compte-rendu.pdf",
                "application/pdf", PDF);

        HttpResponse<byte[]> response = patient.browser()
                .getBytes(documentPath(patient.id(), dto.get("id")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PDF);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).isEqualTo("application/pdf"));
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().firstValue("X-Content-Type-Options"))
                .hasValueSatisfying(value -> assertThat(value).isEqualTo("nosniff"));
        assertThat(response.headers().firstValue("Content-Length"))
                .hasValueSatisfying(value -> assertThat(value).isEqualTo(String.valueOf(PDF.length)));
        assertAttachmentContentDisposition(response, "compte-rendu.pdf");
    }

    @Test
    void aDownloadUsesTheCanonicalContentTypeEvenWhenTheClientLied() throws Exception {
        TestPatient patient = newPatient("doc-download-png");
        Browser staff = staff();
        Map<?, ?> dto = upload(staff, patient.id(), "Scan", "scan.txt", "text/plain", PNG);

        HttpResponse<byte[]> response = patient.browser()
                .getBytes(documentPath(patient.id(), dto.get("id")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PNG);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).isEqualTo("image/png"));
    }

    @Test
    void aDownloadEncodesTheOriginalUnicodeFilenameForRfc5987() throws Exception {
        TestPatient patient = newPatient("doc-download-unicode");
        Browser staff = staff();
        String filename = "compte rendu été.pdf";
        Map<?, ?> dto = upload(staff, patient.id(), "Compte rendu", filename,
                "application/pdf", PDF);

        HttpResponse<byte[]> response = patient.browser()
                .getBytes(documentPath(patient.id(), dto.get("id")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PDF);
        assertAttachmentContentDisposition(response, filename);
    }

    @Test
    void anotherPatientCannotListAndGets404OnTheDownload() throws Exception {
        TestPatient owner = newPatient("doc-owner");
        TestPatient other = newPatient("doc-other");
        Browser staff = staff();
        Map<?, ?> dto = upload(staff, owner.id(), "Prive", "prive.pdf", "application/pdf", PDF);

        assertThat(other.browser().call(documentsPath(owner.id())).statusCode()).isEqualTo(403);
        assertThat(other.browser().getBytes(documentPath(owner.id(), dto.get("id"))).statusCode())
                .as("the download does not reveal that the document exists").isEqualTo(404);
        assertThat(other.browser().getBytes(documentPath(other.id(), dto.get("id"))).statusCode())
                .as("a document id belonging to another patient is not found").isEqualTo(404);
    }

    @Test
    void staffCanListAndDownloadAnyPatientButAnUnknownPatientIs404() throws Exception {
        TestPatient patient = newPatient("doc-staff");
        Browser staff = staff();
        Map<?, ?> dto = upload(staff, patient.id(), "Compte rendu", "compte-rendu.pdf",
                "application/pdf", PDF);

        assertThat(staff.call(documentsPath(patient.id())).statusCode()).isEqualTo(200);
        HttpResponse<byte[]> download = staff.getBytes(documentPath(patient.id(), dto.get("id")));
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(PDF);

        assertThat(staff.call(documentsPath(UUID.randomUUID())).statusCode()).isEqualTo(404);
        assertThat(staff.getBytes(documentPath(UUID.randomUUID(), UUID.randomUUID())).statusCode()).isEqualTo(404);
        assertThat(staff.getBytes(documentPath(patient.id(), UUID.randomUUID())).statusCode()).isEqualTo(404);
    }

    @Test
    void aPractitionerCannotListOrDownload() throws Exception {
        TestPatient patient = newPatient("doc-practitioner");
        Browser staff = staff();
        Browser practitioner = practitioner();
        Map<?, ?> dto = upload(staff, patient.id(), "Compte rendu", "compte-rendu.pdf",
                "application/pdf", PDF);

        assertThat(practitioner.call(documentsPath(patient.id())).statusCode()).isEqualTo(403);
        assertThat(practitioner.getBytes(documentPath(patient.id(), dto.get("id"))).statusCode()).isEqualTo(403);
    }

    @Test
    void deletingAPatientWithDocumentsIs409AndKeepsTheUserAndTheDocument() throws Exception {
        TestPatient patient = newPatient("doc-delete-blocked");
        Browser staff = staff();
        Map<?, ?> dto = upload(staff, patient.id(), "Compte rendu", "compte-rendu.pdf",
                "application/pdf", PDF);

        HttpResponse<String> delete = staff.delete("/api/users/" + patient.id());

        assertThat(delete.statusCode()).as(delete.body()).isEqualTo(409);
        assertThat(Browser.json(delete).get("code")).isEqualTo("DOCUMENTS_PRESENT");
        assertThat(staff.call("/api/users/" + patient.id()).statusCode()).as("the user remains").isEqualTo(200);
        assertThat(patient.browser().getBytes(documentPath(patient.id(), dto.get("id"))).statusCode())
                .as("the document remains").isEqualTo(200);
        assertThat(console.read("/users/{id}", patient.id())).as("the Keycloak account remains").isNotNull();
    }

    @Test
    void deletingAPatientWithoutDocumentsStillSucceeds() throws Exception {
        TestPatient patient = newPatient("doc-delete-free");
        Browser staff = staff();

        assertThat(staff.delete("/api/users/" + patient.id()).statusCode()).isEqualTo(204);

        assertThat(staff.call("/api/users/" + patient.id()).statusCode()).as("the user is gone").isEqualTo(404);
    }

    private static List<Object> ids(Map<?, ?> page) {
        return ((List<?>) page.get("items")).stream()
                .map(item -> (Object) ((Map<?, ?>) item).get("id"))
                .toList();
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(Browser.json(response).get("code")).as(response.body()).isEqualTo(code);
    }
}
