# Décisions produit pour les stories US-03 à US-32

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
