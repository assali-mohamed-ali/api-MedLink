# Décisions produit pour les stories US-03 à US-32

## Mise à jour du 28 novembre 2026 — arbitrage du code owner

Cette section prévaut sur les décisions antérieures en conflit. Le code owner a autorisé les arbitrages techniques restants.

- US-04 : ConsentPolicy version 2026-11-v2, sans promesse non vérifiée d'hébergement ou de destruction. La mise en production nécessite encore les formalités applicables et la validation juridique du texte exact. APP_CONSENT_AVAILABLE reste fermé par défaut.
- US-05 : réconciliation périodique Keycloak et vérification directe avant réservation/réaffectation en staging et production. Les résultats de recherche peuvent refléter l'état du dernier cycle.
- US-15 : confirmation du mot de passe et de la nouvelle boîte, code unique à durée limitée, avertissement à l'ancienne adresse, révocation des sessions, reprise durable (V35).
- US-16 : minimum 20 ans après la dernière activité médicale connue (consultation, compte-rendu, résultat ou document), avec protection des mineurs. La clôture est distincte de la destruction. Le script scripts/retention-readiness.sql est un inventaire en lecture seule, jamais une autorisation de purge. Vérifier l'état réel Keycloak, les obligations et litiges, les décès, les sauvegardes et objets, puis organiser la destruction supervisée et documentée conformément aux formalités applicables, notamment l'article 45 de la loi 2004-63. Aucune purge automatique ni affaiblissement des journaux immuables.
- US-24 : finalisation après la fin du créneau, pas son début ; cela ne prouve pas à lui seul que le patient s'est présenté.
- US-32 : mois civil terminé, un seul indicateur pour chaque mois, résultat (y compris suppression) figé globalement (V36). Seuil de groupe maintenu ; limitation des attaques par différence, pas de garantie formelle de confidentialité différentielle.
- Recette : tests fonctionnels et composants ajoutés, test de migration V23 vers le dernier schéma avec PostgreSQL réel, tests de privilèges du rôle applicatif. CI en attente ; Newman et recette navigateur sur la pile intégrée restent à exécuter avant de déclarer la couverture validée.

## Historique du 27 novembre (supplanté sur les points ci-dessus)

Date : 27 novembre 2026. Source : réponses données par l'équipe aux six questions ouvertes de
`docs/backlog-coverage.md`. Les points marqués **(proposition)** sont des textes ou des durées rédigés
par l'assistant IA pour avancer : ils doivent être validés par Md. Assali avant toute mise en production.

| # | Story | Décision | Statut |
|---|-------|----------|--------|
| 1 | US-04 consentement | Confirmé par Md. Assali, à condition de suivre la loi tunisienne (voir « Sources »). Le texte et son numéro de version sont enregistrés à l'inscription. | texte **mis à jour d'après la loi** ; relecture juridique conseillée |
| 2 | US-16 suppression | Les demandes de suppression sont traitées par un utilisateur du **personnel administratif** : liste, approbation ou refus motivé. L'approbation est impossible tant que la durée de conservation court. La purge elle-même n'est pas codée. | conservation : recommandation INPDP, à confirmer |
| 3 | US-22 rattachement | Un patient est rattaché à un praticien dès qu'il existe entre eux un rendez-vous, **ou** un document, **ou** un résultat / compte-rendu (tout lien antérieur compte). | décidé |
| 4 | US-21 absences | Quand une absence est déclarée, chaque rendez-vous réservé qu'elle couvre est traité par le praticien : report à la semaine suivante ou annulation. | décidé |
| 5 | US-29 retrait de clinique | Les rendez-vous futurs sont réaffectés à un autre praticien, avec la confirmation du patient et du praticien repreneur. Tant que ce flux n'existe pas, le retrait reste refusé (409). | décidé |
| 6 | US-08 / US-05 | Un e-mail de confirmation de rendez-vous et une liste des praticiens sont exigés par les critères d'acceptation. | décidé |

## 1. Consentement (version `2026-11-v1`, texte mis à jour d'après la loi tunisienne)

« Je consens expressément à ce que MedLink traite mes données personnelles et de santé pour gérer mes rendez-vous et mon
dossier médical, conformément à la loi organique n° 2004-63 relative à la protection des données à caractère personnel. Mes
données de santé ne sont accessibles qu'aux professionnels de santé qui me suivent et aux personnes tenues au secret
professionnel, et elles sont hébergées en Tunisie. Elles sont conservées pendant 20 ans à compter de ma dernière
consultation, puis détruites. Je peux demander l'accès à mes données, leur rectification, leur export ou leur suppression
depuis mon profil, et je peux saisir l'Instance nationale de protection des données personnelles (INPDP) en cas de
désaccord. »

On enregistre : le patient, la version du texte, la date et l'heure. Sans consentement, pas de compte (en staging et en
production). Le consentement doit être **exprès** : la case ne doit jamais être pré-cochée côté front.

## 2. Suppression et conservation

- Export : à tout moment, par le patient, pour ses propres données.
- Demande de suppression : le patient la dépose et peut l'annuler tant qu'elle est en attente. Le compte reste actif.
- **Traitement par le personnel administratif** : il voit les demandes en attente (avec la date limite de réponse : un mois,
  délai indiqué par le guide de l'INPDP pour les demandes de droits) et la période de conservation du dossier. Il peut
  **refuser** (motif obligatoire, lu par le patient) ou **approuver**. L'approbation est refusée (409) tant que la
  conservation court.
- Conservation du dossier médical : 20 ans à compter de la dernière consultation, prolongée jusqu'aux 28 ans du patient s'il
  était mineur à cette consultation (recommandation de l'INPDP). La règle « 10 ans après le décès » n'est pas gérée (le décès
  n'est pas enregistré). Les durées sont paramétrables (`app.patient-deletion.*`).
- Approuver ne supprime rien : **la purge n'est pas implémentée** (elle touche aussi les documents chiffrés du stockage).
- Le journal d'audit n'est jamais supprimé et ne contient aucune donnée médicale.

## 3. Activer l'inscription en production (US-04)

État au 27 novembre 2026 : le consentement est **obligatoire dans tous les profils** (dev, test, staging, production :
`app.consent.required=true`). Une inscription sans `consentVersion`, avec une version vide ou une version ancienne reçoit
400 `VALIDATION_FAILED` (champ `consentVersion`) et ne crée rien, ni dans l'API ni dans Keycloak. Chaque patient garde la
version acceptée et l'horodatage (`patients.consent_version`, `patients.consented_at`). Aucune modification ultérieure
du profil ne les réécrit.

En production, les inscriptions restent **fermées** (503 `CONSENT_UNAVAILABLE`) tant que `APP_CONSENT_AVAILABLE` n'est
pas à `true`. Après l'approbation du texte exact et de sa version par Md. Assali et un juriste :

1. Si le texte approuvé diffère de `ConsentPolicy.TEXT`, remplacer le texte **et** la version
   (`ConsentPolicy.CURRENT_VERSION`, par exemple `2026-12-v2`) dans une PR revue. Une version ne désigne jamais deux
   textes différents.
2. Déployer cette version de l'API.
3. Dans l'environnement de production seulement, définir `APP_CONSENT_AVAILABLE=true`, puis redémarrer l'API.
4. Vérifier : `GET /api/auth/consent` renvoie la version approuvée et `required: true`. Une inscription de test sans
   consentement reçoit 400, une inscription avec la version renvoyée reçoit 201.

Les patients inscrits avant un changement de version gardent la version qu'ils ont acceptée. Leur demander d'accepter le
nouveau texte à la prochaine connexion n'est pas implémenté : à décider avec le juriste si le texte change.

## 4. Préférences de notification (US-15)

Décidé et implémenté (V30) : deux e-mails sont optionnels, tous deux actifs par défaut, y compris pour les patients
existants :

- `appointmentReminders` : le rappel dans les 24 heures avant un rendez-vous (US-12). Le job relit la préférence sous le
  verrou du rendez-vous : un patient qui la désactive après le calcul des rappels ne reçoit rien ;
- `examResults` : l'avis « nouveau résultat dans votre espace » (US-25), sans aucun contenu médical.

La confirmation de réservation, les alertes de sécurité, l'activation et la réinitialisation du mot de passe restent
toujours envoyées. MedLink n'envoie pas de SMS : l'e-mail est le seul canal, et l'écran ne propose pas de SMS.

## 5. Changement d'adresse e-mail (US-15) — en attente

Le critère « je modifie … mon e-mail » n'est pas implémenté. L'adresse est l'identifiant de connexion dans Keycloak
(`username = email`, V6). La changer demande un parcours d'identité à approuver : décision **en attente** de Md. Assali.
Proposition :

1. Le patient saisit la nouvelle adresse et son mot de passe actuel (connexion récente exigée, comme l'export).
2. L'API envoie un lien à usage unique et à durée limitée à la **nouvelle** adresse, et un avis sans lien à l'ancienne.
   Elle répond de la même façon que l'adresse soit libre ou non (pas d'énumération de comptes).
3. À l'ouverture du lien, l'API met à jour Keycloak (username, email, `emailVerified`) puis `users.email`, comme une
   opération de compte durable (MLink-72). Elle révoque toutes les sessions du patient.
4. L'audit trace la demande et le changement, sans les adresses.

En attendant, le profil affiche l'adresse en lecture seule et indique de contacter l'accueil de la clinique.

## 6. Étapes d'une demande de suppression (US-16)

Les quatre étapes restent distinctes :

| Étape | Qui | Effet | État |
|---|---|---|---|
| Demande | Patient (connexion récente) | `deletion_requests` PENDING, le compte reste actif | implémenté |
| Décision | Personnel administratif | refus motivé (le patient lit le motif) ou approbation ; 409 `RETENTION_APPLIES` tant que la conservation court | implémenté |
| Clôture du compte | automatique après approbation | dans la transaction de la décision : révocation locale des sessions, désactivation Keycloak enregistrée comme opération durable (exécutée après la validation ou par le worker), `account_closed_at`, e-mail au patient. Réversible : le personnel peut réactiver le compte | implémenté (V30) |
| Anonymisation puis purge | — | irréversibles : identité, documents chiffrés du stockage, dossier | **bloquées** jusqu'à l'approbation de la politique de conservation |

Le dossier médical et le journal d'audit sont conservés à chaque étape.

## 7. Contenu de l'export (US-16, format `medlink-export/2`)

Le JSON contient : le profil, le consentement (version, date), les préférences de notification, les rendez-vous, les
comptes-rendus et les résultats d'examens (textes déchiffrés, auteur), la liste des documents et la route de
téléchargement `/api/patients/{patientId}/documents/{documentId}`, ainsi que l'état de la demande de suppression. Les
fichiers, pièces jointes des résultats comprises, ne sont pas intégrés au JSON. Ils se téléchargent un par un, connecté,
par la route existante (chiffrement au repos, audit de chaque lecture). L'export ne marque pas les résultats comme lus.

## 8. Moment de rédaction du compte-rendu (US-24) — en attente

Le critère produit indique une consultation « terminée ». Le code actuel autorise la rédaction après le début de la consultation, et `ConsultationReportFunctionalTest` protège cette règle. Ne pas modifier cette condition ni l'immutabilité des comptes-rendus finalisés avant confirmation de Md. Assali. La recette doit vérifier l'ordre antéchronologique et l'audit des lectures/écritures, indépendamment de cette décision.

## Sources (recherche du 27 novembre 2026)

- Guide pratique de l'INPDP à destination des médecins libéraux (Conseil de l'Europe / INPDP) :
  <https://rm.coe.int/protection-des-donnees-personnelles-guide-pratique-a-destination-des-m/1680a8dd33> : conservation de
  20 ans à compter de la dernière consultation (jusqu'à 28 ans pour un mineur, 10 ans après le décès si celui-ci survient dans
  les 10 ans), réponse à une demande de droits sous un mois, traitement des données de santé soumis à déclaration et
  autorisation préalable de l'INPDP (articles 7 et 14 de la loi 2004-63), hébergement par un hébergeur agréé,
  transferts à l'étranger encadrés, traitement par des médecins ou des personnes soumises au secret (article 63).
- Loi organique n° 2004-63 du 27 juillet 2004 : <https://www.ins.tn/sites/default/files/2020-04/Loi%2063-2004%20Fr.pdf>
  (non lue en entier : le résumé du consentement exprès, de la destruction à l'expiration de la conservation et de
  l'autorisation de transfert à l'étranger vient d'un article de blog secondaire).

## Points à faire vérifier

- Un juriste doit relire le texte et les durées avant la production : la loi n'a pas pu être lue en entier ici.
- Le traitement de données de santé demande une **déclaration et une autorisation préalable de l'INPDP** : démarche de
  l'équipe, hors code.
- Le personnel administratif ne devrait pas avoir un accès global aux données de santé (recommandation du guide) : à vérifier
  pour les écrans du personnel.
- Les données doivent réellement être hébergées en Tunisie (sinon le texte de consentement est faux et un transfert à
  l'étranger demande une autorisation).
