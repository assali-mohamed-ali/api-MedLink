# Rapport d'implémentation du prompt backlog (US-01 à US-32)

Date : 27 novembre 2026. Base : `master` d'api-MedLink. Ce rapport décrit le travail fait avec l'aide d'un assistant IA, une
branche par story, **sans aucune modification de `master`, `staging` ou `prod`**.

## Limite essentielle : rien n'a été compilé ni exécuté

L'environnement de travail n'avait ni accès à Maven Central (proxy sortant en 403) ni Docker. **Aucun code ci-dessous n'a été
compilé, aucun test n'a été lancé.** Chaque branche doit passer `./mvnw -B verify` puis
`./mvnw -B test -Dsurefire.runOrder=reversealphabetical` (Docker démarré) avant toute fusion. Aucune affirmation de
réussite n'est faite ici. Le frontend (`medlink-dashboard`) n'a pas pu être modifié : l'accès GitHub était refusé.

## Où tout se trouve : une seule branche

**`feat/backlog-us01-us32`** regroupe tout le travail ci-dessous (fusion de toutes les branches, conflit de `MailService`
résolu, réservation par le personnel reliée à l'e-mail de confirmation). C'est la branche à reprendre. Les branches par
story restent en place pour relire story par story ; aucune n'a été supprimée.

Seule vérification faite : une **analyse syntaxique** de tout le code fusionné avec `javac` (elle a trouvé et permis de
corriger une accolade perdue à la fusion de `MailService`). Ce n'est pas une compilation : les dépendances n'étaient pas
disponibles, donc les erreurs de types ou d'appels ne sont pas détectées.

## Branches livrées

| Story | Branche | Contenu | Migration |
|---|---|---|---|
| US-15 profil patient | `feat/patient-profile` | `GET`/`PUT /api/patients/me` : téléphone et adresse | aucune |
| US-29 cliniques d'un praticien | `feat/practitioner-clinics` | `PUT /api/admin/practitioners/{id}/clinics` ; retrait refusé (409) tant que des créneaux existent | aucune |
| US-22 recherche de patients | `feat/practitioner-patient-search` | `GET /api/practitioner/patients?q=` ; lien = rendez-vous, compte-rendu ou document | aucune |
| US-08 confirmation | `feat/booking-confirmation-mail` | e-mail de confirmation après réservation ou report | aucune |
| US-04 consentement | `feat/consent` | version + date du consentement, `GET /api/auth/consent` ; obligatoire en staging et production | V24 |
| US-03 mot de passe oublié | `feat/forgot-password` | `POST /api/auth/password/forgot` et `/reset` | V25 |
| US-16 export | `feat/patient-export` | `GET /api/patients/me/export` (JSON) | aucune |
| US-16 demande de suppression | `feat/patient-deletion-request` | côté patient : `POST`/`GET`/`DELETE /api/patients/me/deletion-request` ; côté **personnel administratif** : `GET /api/admin/deletion-requests` et `POST /api/admin/deletion-requests/{patientId}/decision` (approbation bloquée tant que la conservation court, refus motivé) ; **ne supprime rien** | V26 |
| US-21 absences | `feat/practitioner-absences` | `POST /api/practitioner/absences` (ferme les créneaux libres, liste les rendez-vous réservés à traiter) ; `POST /api/practitioner/appointments/{id}/absence-decision` : annuler, ou reporter à la même heure la semaine suivante (même clinique, créneau libre requis) ; le patient reçoit un e-mail | V27 |
| US-30 rendez-vous pris par le personnel | `feat/staff-booking` | `POST /api/admin/appointments` `{patientId, slotId}` : mêmes règles et verrous que la réservation du patient ; le rendez-vous est au patient, `booked_by` = le membre du personnel (audit) ; e-mail de confirmation sur la branche regroupée | aucune |
| US-13 / US-25 résultats d'examens | `feat/exam-results` | praticien : `POST`/`GET /api/patients/{id}/results` (titre, type, date d'examen, résultat, document du patient en option) ; patient : `GET /api/results/me` (nouveau / vu) ; titre, type et résultat chiffrés | V28 |
| US-29 réaffectation (**inachevée**) | `feat/appointment-reassignment` | seulement le schéma, l'entité et le dépôt ; ni service, ni contrôleurs, ni test | V29 |
| Décisions produit | `docs/product-decisions` | les six décisions de l'équipe | — |
| Audit initial | `docs/backlog-coverage` | matrice de couverture US-01 à US-32 | — |

US-05 (liste des praticiens) : déjà couverte (filtres de recherche de créneaux côté patient, liste côté personnel).

## Ordre de fusion et conflits

- Fusionner `feat/consent` (V24) **avant** `feat/forgot-password` (V25) puis `feat/patient-deletion-request` (V26) : Flyway
  refuse un script plus ancien appliqué après un plus récent.
- `feat/patient-profile` et `feat/practitioner-clinics` ajoutent le même helper `putJson` dans `FunctionalTestBase` (texte
  identique) : la fusion du second ne devrait pas poser de conflit.
- `MailService` : `feat/booking-confirmation-mail`, `feat/forgot-password` et `feat/practitioner-absences` ajoutent chacune des méthodes
  au même endroit : conflit de fusion attendu, à résoudre en gardant les trois blocs. Migrations : V24 (consentement), V25 (mot de passe
  oublié), V26 (suppression), V27 (absences), V28 (résultats), V29 (réaffectation). Sur la branche regroupée, tout est déjà dans
  le bon ordre.
- `SecurityConfig` : chaque branche ajoute sa règle à un endroit différent ; vérifier tout de même la fusion.

## Non fait

- **US-16 purge définitive** : volontairement non implémentée. Le personnel peut approuver une demande quand aucune conservation
  ne s'applique, mais rien n'est supprimé : une purge touche aussi les documents chiffrés du stockage objet.
- **US-29 réaffectation** des rendez-vous futurs vers un autre praticien avec double confirmation (décidée, non codée).
- **US-21** : un créneau ouvert plus tard pendant une absence n'est pas encore bloqué.
- **US-29 réaffectation** : seuls le schéma (V29), l'entité `Reassignment` et son dépôt existent. Il reste : la proposition par
  le personnel (`POST /api/admin/appointments/{id}/reassignments` avec un créneau libre d'un autre praticien de la même
  clinique), l'acceptation ou le refus du patient (`/api/reassignments/**`, règle `SecurityConfig` à ajouter) et du praticien
  repreneur (`/api/practitioner/reassignments`), puis le déplacement quand les deux ont accepté (même logique de verrous que
  le report d'un patient), les e-mails et le test. Le retrait d'une clinique reste refusé (409) tant que le praticien y a des
  créneaux, même passés : la clé étrangère de V13 l'impose. Le permettre avec un historique demande une décision de schéma
  (par exemple un lien clinique « inactif »).
- **US-13 / US-25** : pas d'envoi de fichier par le praticien (il rattache un document déjà déposé par le personnel) ; pas de
  pagination de la liste.
- **US-30** : pas de vérification que le compte du patient est actif.
- **Frontend** : aucune page (profil, consentement, mot de passe oublié `/reinitialiser-mot-de-passe`, export, demande de
  suppression, recherche de patients) ; le front doit envoyer `consentVersion` à l'inscription.
- **Postman** : collection non mise à jour pour les nouveaux endpoints (le consentement est facultatif en dev, donc les
  appels existants restent valides).
- **YouTrack / issues GitHub** : non consultés.

## Points à valider par Md. Assali

- Le texte de consentement (version `2026-11-v1`), maintenant aligné sur la loi organique 2004-63 et le guide de l'INPDP : à
  faire relire par un juriste (la loi n'a pas pu être lue en entier).
- La conservation de 20 ans après la dernière consultation (jusqu'à 28 ans pour un mineur) : recommandation de l'INPDP, pas un
  texte de loi lu directement.
- La demande de suppression laisse le compte actif (pour pouvoir l'annuler) ; c'est le personnel qui décide.
- La déclaration et l'autorisation préalable de l'INPDP pour les données de santé, et l'hébergement réel en Tunisie.

Sources et détails : `docs/product-decisions.md`.

## Déclaration IA

Tout ce travail a été produit avec un outil d'IA générative. Dans chaque PR : cocher « J'ai utilisé des outils d'IA
générative pour contribuer à cette PR » et laisser « Je confirme avoir relu, compris et validé… » décochée jusqu'à une relecture
humaine réelle.
