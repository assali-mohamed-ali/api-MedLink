# Tests manuels des nouveaux endpoints (à faire après `./mvnw -B verify`)

Pré-requis : la pile de dev (`scripts/setup-dev.sh` ou `.ps1`), un navigateur ou `curl` avec un cookie de session et l'en-tête
`X-XSRF-TOKEN` pour chaque écriture (voir `GET /api/auth/csrf`). Rien ici n'a encore été exécuté.

| Endpoint | Rôle | Cas à vérifier |
|---|---|---|
| `GET /api/patients/me` | patient | 200 avec téléphone et adresse ; 403 pour praticien et personnel ; 401 sans session |
| `PUT /api/patients/me` | patient | modifier téléphone/adresse (200) ; valeur vide efface ; téléphone invalide → 400 ; un `id`, `role` ou `email` dans le corps est ignoré |
| `PUT /api/admin/practitioners/{id}/clinics` | personnel | remplacer la liste (200) ; retirer une clinique qui a des créneaux → 409 `CLINIC_HAS_SLOTS` ; ids inconnus → 404 ; liste vide → 400 |
| `GET /api/practitioner/patients?q=` | praticien | ne renvoie que les patients liés (rendez-vous réservé, compte-rendu ou document) ; `%` et `_` pris littéralement ; `q` > 100 → 400 ; 403 pour patient et personnel |
| Réservation / report | patient | un e-mail « Confirmation de votre rendez-vous MedLink » arrive (Mailpit) ; une réservation refusée n'en envoie pas |
| `GET /api/auth/consent` | public | texte, version, `required` |
| `POST /api/auth/register` | public | avec `consentVersion` actuelle : 201 et ligne `patients.consent_version` ; ancienne version → 400 ; en staging/prod sans consentement → 400 |
| `POST /api/auth/password/forgot` | public | 202 et même message pour un compte connu et inconnu ; un seul mail par minute |
| `POST /api/auth/password/reset` | public | lien valable 1 h, une seule fois (410 ensuite) ; mot de passe faible → 400 et lien encore utilisable ; l'ancienne session est refusée |
| `GET /api/patients/me/export` | patient | JSON : profil, rendez-vous, comptes-rendus, liste des documents ; rien d'un autre patient ; ligne d'audit `DATA_READ` / `PATIENT_EXPORT` |
| `POST`/`GET`/`DELETE /api/patients/me/deletion-request` | patient | la demande ne supprime rien ; deux demandes gardent la première ; annulation → 204 |

Vérifier aussi dans `audit_entries` : aucune donnée médicale, uniquement des identifiants.
