# Staging and production preparation — plan and hand-over

Tickets MLink-168 to MLink-173 (YouTrack, assigned to Mohamed, subtasks of MLink-5). Branch `chore/vault-deploy`
(api-MedLink, from `master`) for MLink-168, 169, 171, 172; `chore/cd-deploy` in `medlink-dashboard` for MLink-170.
Background, design and test results: the section "Staging and production preparation" of `report.md`. Runbooks:
`docs/operations/vault.md`, `docs/operations/servers.md`.

## Hand-over for the next session (2026-11-18, evening)

**Read first:** this file, then `report.md` (Status + that section), then the two runbooks.

**State of the branches**

| Branch | State |
|---|---|
| `chore/vault-deploy` | MLink-168 pushed (`b971baf`). **MLink-171 committed but NOT pushed**: `6a16ffd` (red), `7d636ba` (green), `8e8b140` (docs) and this hand-over. The full suite was stopped by the machine running out of memory, so the push still waits for `./mvnw -B clean verify` + reversed order (CLAUDE.md). No PR yet: one PR for the branch once MLink-169 is in. |
| `fix/db-hardening` (PR #126) | Code owner's review fixed and pushed (`7ccfbef` red, `5fdf58a` green, `1a6018a` docs): 788/0 both orders, all checks green, re-review requested, both inline comments answered. Still "changes requested" until he re-reviews. |
| `chore/db-backup-restore` (PR #129) | Waiting for the code owner's review. |
| PR #125 (a teammate's, V17) | Also "changes requested". It must merge **before** #126 (V18), so it holds the order #125 → #126 → #129. |

**YouTrack** (checked 2026-11-18 evening, no change by anyone else): MLink-171 *En cours* (move to *À vérifier* after the
push, with the commit link); MLink-168, 164, 165, 166 *À vérifier*; MLink-169, 170, 172, 173 *Ouvert*.
MLink-171 shows a short "Doublon" in its history: a wrong click, corrected at once.

**Next steps, in order**

1. Free memory (Chrome, Edge, `wsl --shutdown`; about 3 GB free needed), start Docker Desktop.
2. On `chore/vault-deploy`: `./mvnw -B clean verify`, then `./mvnw -B test -Dsurefire.runOrder=reversealphabetical`
   (JDK 17). Green: `git pull --no-rebase`, `git push`. MLink-171 to *À vérifier* with the commit link.
3. **MLink-170** (front CD) in `medlink-dashboard`: move it to *En cours*; branch `chore/cd-deploy` from that repo's
   default branch after reading its README/CLAUDE.md and branch rules; a workflow that builds and scans the image,
   pushes it to GHCR, and deploys to staging on a push to `staging` (production from a version tag), reading
   `medlink/<env>/front` from Vault with GitHub OIDC (role `front-staging` / `front-production`, audience
   `medlink-vault`, `permissions: id-token: write`). Container on `127.0.0.1:8081` (nginx-unprivileged listens on
   8080 inside). Check that the app calls `/api` on its own origin (the session cookie needs it). Red test, green,
   actionlint, local image build.
4. **MLink-169** once #126 is merged (it changes the same `.github/actions/deploy-compose/action.yml`):
   `git merge origin/master` into `chore/vault-deploy`, then:
   - `cd.yml`: a `deploy-staging` job (master has none, only `deploy-prod`), environment `staging`;
   - the action logs in to Vault (`hashicorp/vault-action`, method jwt, path `jwt-github`, role `api-<env>`), reads
     `medlink/<env>/api`, writes `.env` (600) and `.backup.env` (only `BACKUP_PASSPHRASE`, read by the backup job)
     on the host, user `deploy`, under `/opt/medlink/api`; `DEPLOY_ENV_FILE` goes away;
   - non-secret settings per environment in the repository (URLs, ports, profile, `TRUSTED_PROXIES`: `127\.0\.0\.1`
     on staging, `172\.31\.40\.60` in prod, mail host and account), not in Vault;
   - copy `scripts/db-backup.sh` and `db-restore.sh` to the host (they arrive with #129);
   - add `restart: unless-stopped` to the long-running services of `docker-compose.yml` (found missing);
   - prod: API stack on api-medlink bound for the front host only; TLS ends on frontend-medlink.
   Then open the PR for `chore/vault-deploy` (AI usage box ticked, review box left for Mohamed).
5. After the repo owner's part (below): apply Vault on the host (`docs/operations/vault.md`, "First set-up"); Mohamed
   types the Gmail app password himself at `seed-env.sh`'s hidden prompt; prepare each host with
   `deploy/prepare-host.sh` (`docs/operations/servers.md`); first staging deploy and its checks (MLink-173), then prod.

**Waiting on the repo owner (assali-mohamed-ali, the only one with the AWS console and Vault's root token)**, message
sent by Mohamed on 2026-11-18: re-review #126, review #129, merges #125 → #126 → #129; Elastic IPs then resize staging
and api (t3.medium, 20 GB); a `medlink-setup` token (8 h, as a file); who holds the 5 unseal keys; `staging` brought up
to `master`, branch rules, GitHub environments `staging` and `production` in both repositories; an S3 bucket
(versioned) for backups and Vault snapshots; one production branch name (`prod` here, `Production` in the front).

## Rules for this work

- Never handle a password or token in a chat or a file: the Gmail app password is typed by Mohamed at the hidden
  prompt; the Vault setup token comes as a file from the repo owner and is revoked after use; generated secrets stay
  on the vault host.
- The EC2 launch keys live on Mohamed's machine only (not in git); SSH as `ubuntu`. Check each host key against the
  fingerprints in `docs/operations/servers.md` before trusting a host.
- Nothing destructive on a host without asking Mohamed first. Every host script is tested on a throwaway Ubuntu
  26.04 container first and is safe to run again.

## Pitfalls met (Windows, Git Bash)

- Docker from Git Bash: wrap it as `MSYS_NO_PATHCONV=1 docker ...` and mount with `$(pwd -W)`; never export
  `MSYS_NO_PATHCONV` for a whole script (native tools such as openssl then get untranslated `/tmp` paths).
- Never call `python`/`python3` in Git Bash (the Windows Store alias hangs). `node` and `perl` are there; prefer
  `sed -n 'Np'` + line-numbered edits over clever quoting when breaking a rule on purpose, and check the edit applied.
- Piping a script to ssh from PowerShell adds a BOM and mangles it: run ssh from Git Bash, `ssh ... 'bash -s' < file`.
- The postgres image trusts connections from 127.0.0.1: test a role's password from another container.
- YouTrack: create tickets with `/newIssue?project=MLink&summary=...&description=...` (Markdown); bulk-assign with the
  command `Responsable Mohamed.BenNaima subtask of MLink-5`; pick dropdown values from a screenshot, not from hidden
  list items (that is how the "Doublon" click happened).
- Run the full suite only with about 3 GB free; Claude Code stops background jobs when memory runs out.
