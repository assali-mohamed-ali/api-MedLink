# Décisions produit pour les stories US-03 à US-32

Date : 27 novembre 2026. Source : réponses données par l'équipe aux six questions ouvertes de
`docs/backlog-coverage.md`. Les points marqués **(proposition)** sont des textes ou des durées rédigés
par l'assistant IA pour avancer : ils doivent être validés par Md. Assali avant toute mise en production.

| # | Story | Décision | Statut |
|---|-------|----------|--------|
| 1 | US-04 consentement | Le texte de consentement et un numéro de version de politique sont enregistrés à l'inscription (voir ci-dessous). | texte et version : **proposition** |
| 2 | US-16 suppression | Une politique de conservation est définie (voir ci-dessous) ; l'export des données ne dépend pas d'elle. | durées : **proposition** |
| 3 | US-22 rattachement | Un patient est rattaché à un praticien dès qu'il existe entre eux un rendez-vous, **ou** un document, **ou** un résultat / compte-rendu (tout lien antérieur compte). | décidé |
| 4 | US-21 absences | Quand une absence est déclarée, chaque rendez-vous réservé qu'elle couvre est traité par le praticien : report à la semaine suivante ou annulation. | décidé |
| 5 | US-29 retrait de clinique | Les rendez-vous futurs sont réaffectés à un autre praticien, avec la confirmation du patient et du praticien repreneur. Tant que ce flux n'existe pas, le retrait reste refusé (409). | décidé |
| 6 | US-08 / US-05 | Un e-mail de confirmation de rendez-vous et une liste des praticiens sont exigés par les critères d'acceptation. | décidé |

## 1. Consentement (proposition de texte, version `2026-11-v1`)

« J'accepte que MedLink traite mes données personnelles et de santé pour gérer mes rendez-vous et mon dossier
médical, conformément à la politique de confidentialité en vigueur. Mes données sont hébergées en Tunisie, ne sont
partagées qu'avec les professionnels de santé qui me suivent, et je peux à tout moment demander leur export ou leur
suppression depuis mon profil. »

On enregistre : le patient, la version du texte, la date et l'heure du consentement. Sans consentement, pas de compte.

## 2. Conservation (proposition)

- Export : à tout moment, par le patient, pour ses propres données (profil, rendez-vous, documents, comptes-rendus).
- Demande de suppression : le compte est désactivé tout de suite ; la suppression définitive a lieu après un délai de
  réflexion de 30 jours (annulable par le patient pendant ce délai).
- Données médicales : conservées pendant la durée légale de conservation du dossier médical, puis supprimées. Durée à
  confirmer par Md. Assali avant la mise en production (valeur paramétrable, pas figée dans le code).
- Le journal d'audit n'est jamais supprimé et ne contient aucune donnée médicale.
