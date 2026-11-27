# MedLink manual acceptance

Use a local isolated development stack with PostgreSQL, Keycloak, MinIO and Mailpit. Start the API with the existing development setup script and configure the dashboard to proxy /api to that instance. Use only synthetic accounts, clinics and medical fixtures. Record the two Git SHAs, scenario, timestamp, result and test evidence without including credentials, tokens or medical content.

## A. Patient

1. Open Inscription with consent required. Confirm the box starts unchecked. Submit otherwise valid registration without the policy version through the API and verify rejection. Accept and register through the browser. Activate the account through Mailpit.
2. Log in, reload, edit telephone and address in Profil, and reload again. Check that the original names and email remain intact. In Notifications, turn reminders off, reload and check it stays off; book an appointment within 24 hours and check Mailpit receives the confirmation but no reminder.
3. Filter practitioner and clinic, find a future slot, review and book. Confirm receipt, a Mailpit message and the appointment in Mes rendez-vous.
4. Reschedule to a valid slot with the same practitioner, then cancel before the deadline. Verify the prior slot release, status history and practitioner alert. Repeat with a booking inside the cancellation deadline and verify refusal.
5. Request recovery with known and unknown synthetic addresses. Compare public messages. Open the actual reset link, replace the password, verify the previous session is revoked and retry the used link.

## B. Practitioner

1. Start login for an enrolled practitioner. Try the agenda before completing MFA. Complete TOTP and check access.
2. Open availability in two assigned clinics. Attempt a conflicting slot and confirm the original entry remains. Filter the agenda by clinic and inspect its cancelled states.
3. Declare an absence covering a free slot and a booked appointment. Verify the system closes the free slot and requires a decision on the booked appointment. Try to open or reopen a slot within the absence. Resolve bookings one at a time and confirm Mailpit delivery.
4. Search Mes patients by name, full name and the displayed record number (MED-…); use linked homonyms and an unrelated synthetic patient. Set a small page size through the API and confirm the total and that every returned patient opens the same record. Verify the foreign record is refused and successful reads are audited. Add a consultation report after the currently permitted start, not before; the completed-versus-started decision is pending. Verify newest-first history and read/write audit without medical text.

## C. Examination results

1. Upload a synthetic document as staff. As a linked practitioner, list and select it, then upload a second valid synthetic attachment from the patient record. Verify direct practitioner download is refused and the patient can download the exact bytes. Test an invalid file and foreign patient upload/list.
2. Publish a result with title, type, date and content. Re-send the identical requestId and verify 200 with the same id, not a second result; reuse it for another linked patient and expect 409 REQUEST_ID_REUSED. Check paged practitioner history and the patient association.
3. Publish enough results to span multiple pages. Open Mes résultats as the patient, verify items/page/size/total/unseen, expand and download. Read one page and check only those results become seen; navigate to the next page and confirm the remaining count. Invalid page/size returns 400. Compare downloaded bytes and inspect the audit.
4. Confirm Mailpit receives a notice only after successful publication, without medical content. Disable exam-result notifications, publish another result and confirm no notice; simulate a temporary SMTP outage on an isolated stack and verify retry with stable Message-ID. A repeated requestId must not create a second result or a new notification event.
5. Repeat as another patient, unrelated practitioner and wrong-role caller. Verify no foreign content or attachment is disclosed. Record browser and Newman evidence separately; none of these updated scenarios are marked passed until executed.

## D. Staff-assisted booking

1. Complete staff MFA. Open Réserver pour un patient. Search for an eligible synthetic patient, select practitioner, specialty and clinic, and review a free slot.
2. Confirm the appointment and note its reference. As the patient and practitioner separately, find the same appointment in history and agenda.
3. Check the audit event for the staff actor and use Mailpit to verify confirmation and reminder. Race a second eligible booking for the same free slot and require exactly one success.

## E. Clinic assignments and supervision

1. Open Affectations des praticiens and add a new clinic. As the practitioner, create availability there. Try to remove that assignment while slots exist and verify a 409 with the schedule preserved.
2. Compare the read-only clinic activity counts with the synthetic timetable. Exercise an anonymized CSV for sufficient data and verify low-volume filters return the expected suppression behavior.

## F. Personal data

1. From a recently authenticated patient account, download the profile JSON and check that it contains only that patient’s records. After the freshness window, repeat and verify reauthentication is required.
2. Confirm a deletion request and check it from staff review. Try accepting a request covered by retention. Reject it with a synthetic reason and verify the patient sees the decision. Test withdrawal while pending. Verify appointments, documents and audit rows remain intact.
3. With a patient who never had a consultation, approve the request. Verify the patient's open tab is logged out at the next action, a new login is refused, Mailpit holds "Votre compte MedLink est clôturé", and the patient's rows, appointments and audit entries remain. Check the export JSON (format `medlink-export/2`) of another patient lists results, consent and preferences, and that their results stay marked new in Mes résultats.

## G–H. Security and regression

Run the API access matrix against anonymous, patient, practitioner and staff accounts, including CSRF handling and cross-patient document accesses. Verify initialization on a new database and upgrade from a synthetic pre-change V23 snapshot. Exercise expired sessions, MFA pending, rate limits and repeated writes. Use the project’s passive ZAP baseline against the isolated stack and record the result. The dashboard’s [existing manual guide](https://github.com/assali-mohamed-ali/medlink-dashboard/blob/master/docs/MANUAL-TESTING.md) contains the role workflow baseline.
