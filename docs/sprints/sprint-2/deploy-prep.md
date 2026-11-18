# Staging and production preparation — plan and hand-over

Tickets MLink-168 to MLink-173 (YouTrack, assigned to Mohamed, subtasks of MLink-5). Branch `chore/vault-deploy`
(api-MedLink, from `master`) for MLink-168, 169, 171, 172; `chore/cd-deploy` in `medlink-dashboard` for MLink-170.
Background, design and test results: the section "Staging and production preparation" of `report.md`. Runbooks:
`docs/operations/vault.md`, `docs/operations/servers.md`.

## Hand-over for the next session (2026-11-19)

**Read first:** this file, then `report.md` (Status + that section), then the two runbooks.

**State of the branches**

| Branch | State |
|---|---|
| `chore/vault-deploy` | MLink-168 (`b971baf`) and MLink-171 (`6a16ffd` red, `7d636ba` green) pushed. `master` merged after #125, #126 and #129 (`7b480ce`): `clean verify` and the reversed order 845/0; Vault, host and backup script tests pass. No PR yet: one PR for the branch once MLink-169 is in. |
| `fix/db-hardening` (PR #126) | Merged into `master` on 2026-11-18. |
| `chore/db-backup-restore` (PR #129) | Merged into `master` on 2026-11-18. |
| PR #125 (a teammate's, V17) | Merged into `master` on 2026-11-18. |

**YouTrack** (checked 2026-11-19, no change by anyone else): MLink-171, 168, 164, 165 and 166 *À vérifier*;
MLink-169, 170, 172, 173 *Ouvert*.
MLink-171 shows a short "Doublon" in its history: a wrong click, corrected at once.

**Next steps, in order**

1. Before any full suite: free memory (Chrome, Edge, `wsl --shutdown`; about 3 GB free needed), start Docker Desktop.
2. Done 2026-11-19: `chore/vault-deploy` merged with `master`, both test orders 845/0, pushed, MLink-171 to
   *À vérifier*.
3. **MLink-170** (front CD) in `medlink-dashboard`: move it to *En cours*; branch `chore/cd-deploy` from that repo's
   default branch after reading its README/CLAUDE.md and branch rules. **The front already has CD**:
   `container-security.yml` on its `master` builds, scans, signs, pushes to GHCR and deploys (staging on a push to
   `staging`, production from `prod` or a `v*` tag). Extend it, no second workflow. To change: publish the container
   on `127.0.0.1:8081` only (`deploy/docker-compose.frontend.yml` publishes it on every interface today). No Vault step:
   `seed-env.sh` stores nothing for the front. The app already calls `/api` on its own origin. Red test, green,
   actionlint, local image build.
4. **MLink-169** (#126 is merged, nothing blocks it):
   on `chore/vault-deploy`, after `git merge origin/master` if `master` moved:
   - `cd.yml`: a `deploy-staging` job (master has none, only `deploy-prod`), environment `staging`;
   - the action logs in to Vault (`hashicorp/vault-action`, method jwt, path `jwt-github`, role `api-<env>`), reads
     `medlink/<env>/api`, writes `.env` (600) and `.backup.env` (only `BACKUP_PASSPHRASE`, read by the backup job)
     on the host, user `deploy`, under `/opt/medlink/api`; `DEPLOY_ENV_FILE` goes away;
   - non-secret settings per environment in the repository (URLs, ports, profile, `TRUSTED_PROXIES`: `127\.0\.0\.1`
     on staging, `172\.31\.40\.60` in prod, mail host and account), not in Vault;
   - copy `scripts/db-backup.sh` and `db-restore.sh` to the host;
   - add `restart: unless-stopped` to the long-running services of `docker-compose.yml` (found missing);
   - prod: API stack on api-medlink bound for the front host only; TLS ends on frontend-medlink.
   Then open the PR for `chore/vault-deploy` (AI usage box ticked, review box left for Mohamed).
5. After the repo owner's part (below): apply Vault on the host (`docs/operations/vault.md`, "First set-up"); Mohamed
   types the Gmail app password himself at `seed-env.sh`'s hidden prompt; prepare each host with
   `deploy/prepare-host.sh` (`docs/operations/servers.md`); first staging deploy and its checks (MLink-173), then prod.

**Waiting on the repo owner (assali-mohamed-ali, the only one with the AWS console and Vault's root token)**, message
sent by Mohamed on 2026-11-18. His answers: #125, #126, #129 merged; no Elastic IPs for now; staging and api being resized,
new IPs to come; unseal keys not assigned yet; one production branch name, `prod`. He proposes Terraform and GitHub Actions for everything from the EC2 hosts to the deploy (scope not agreed yet). Still open: a `medlink-setup` token (8 h, as a file); `staging` brought up
to `master`, branch rules, GitHub environments `staging` and `production` in both repositories; an S3 bucket
(versioned) for backups and Vault snapshots; the front's `Production` branch renamed `prod`.

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
