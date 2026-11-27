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
4. Search Mes patients for a linked and an unrelated patient. Open a permitted record, confirm an audit entry, add a consultation report after the permitted stage, and reload history.

## C. Examination results

1. Upload a synthetic document under its correct patient with an administrative test account.
2. From a linked practitioner’s patient record, prepare an exam result with title, exam type, date and content. Associate the allowed document identifier and confirm publication. Check practitioner history.
3. Open Mes résultats as the patient, expand the result, and download the attachment. Verify the publication fields and compare the file to the original fixture.
4. Repeat the read as another patient, the publication as an unrelated practitioner, and attachment assignment using another patient’s document. Verify refusal without disclosure of the foreign content.

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
