# Staging and production preparation — plan and hand-over

Tickets MLink-168 to MLink-173 (YouTrack, assigned to Mohamed, subtasks of MLink-5). Branch `chore/vault-deploy`
(api-MedLink) for MLink-168, 169, 171, 172, 173; `chore/cd-deploy` in `medlink-dashboard` (merged, #20) for MLink-170.
Background and test results: `report.md`, section "Staging and production preparation". Runbooks:
`docs/operations/vault.md`, `docs/operations/servers.md`, `docs/DEPLOYMENT.md`.

## Hand-over for the next session (2026-11-22)

**Start every session with:** GitHub (new commits on `master`/`staging`/`prod`, PRs, reviews, CI) and YouTrack (new
comments on MLink-5, MLink-9, MLink-168..174), then `git fetch`, and free RAM (about 3 GB before the test suite). The
full reference with diagrams, every address and where each secret lives is a PDF on Mohamed's machine, not in git:
`Docs/Deployment/MedLink-Infrastructure-and-Deployment-Reference.pdf`.

**Rules from Mohamed:** deploys go **only through the CD workflows** (`staging` then `prod` branches), never by hand
over SSH (host preparation, Vault configuration and diagnostics over SSH are fine). The production images deployed by
hand on 2026-11-19 stay as they are until the whole app ships through CD. Never handle a password, private key or
unseal key in chat.

### Where things stand

| Part | State |
|---|---|
| Production (api-medlink `13.50.114.144` + frontend-medlink `56.228.94.12`) | Works end to end, healthy. Images `medlink-api:0be7983`, `medlink-front:72a61af`, deployed by hand on 2026-11-19 |
| Staging (`13.51.30.39`) | Dashboard and Postgres up; API 502 (never deployed: every CD run stops at the Vault login) |
| Vault (`56.228.74.131`) | Unsealed, secrets seeded, #133's roles live (2026-11-20). **#137's roles not applied yet** (immutable `sub` of GitHub's OIDC token): every deploy job gets HTTP 400 at the login. Disk: 892 MB free |
| Front host | 1.9 GB free disk |
| GitHub environments | `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PATH`, `DEPLOY_HEALTH_URL`, `DEPLOY_KNOWN_HOSTS`, **`DEPLOY_SSH_KEY` set in all four (2026-11-20, checked)** |
| Specialties | MLink-174 merged (#134): V19, 15 specialties in every environment |

| Git | State |
|---|---|
| `integration/whole-app` (both repos, from `staging`) | Every open piece of work merged, conflict-free: api PR **#139**, dashboard PR **#28**, both → `staging`. Local: api 1018+ tests in both orders, dashboard 475 |
| api #139 | MLink-9's MinIO client moved to 9.0.4 (Trivy 8 HIGH/CRITICAL → 0; kotlin 2.4.21 for Dependency-Check). Patient documents in the CD deploy, **option b** (Mohamed, 2026-11-22): each API host runs MinIO on the compose network only, plain HTTP inside the host, documents AES-256-GCM sealed by the API; Vault generates `APP_DOCUMENTS_ENCRYPTION_KEY`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` (`seed-env.sh`) |
| dashboard #28 | Green; publish job now has `artifact-metadata: write` (the 2026-11-22 staging publish failed with "tlog entry 404", like the API before #138) |

### Next, in order

1. **Vault** (needs Mohamed's setup token: `Docs/Deployment/new-setup-token.sh` fed with three unseal keys). Then, from
   `staging`'s (or #139's) `vault/`: `setup.sh` (#137 roles), `seed-env.sh staging` and `seed-env.sh prod` (adds only
   the three document/MinIO values, never changes an existing one), read one role back, revoke the token.
2. **Owner settings**: `CROSS_REPO_TOKEN` in both repositories; `AUTO_DEPLOY_PROD=false` in both, so the first CD run
   stops after staging and production keeps its images.
3. **Disk**: look at what fills the Vault and front hosts (MinIO also adds a volume on each API host); delete only
   after asking.
4. **Merge #139 and #28 into `staging`** (owner review, AI boxes ticked by Mohamed). CD deploys staging, smoke-tests,
   waits for the other repository. Check MLink-172 (a signup mail arrives), MLink-173 (the PDF's checks), MLink-174
   (specialties listed), a document upload and read back (MLink-9).
5. **Production**: when the team says go, `staging` → `prod` through the workflow (`AUTO_DEPLOY_PROD` back on, or the
   promotion PR). CD replaces the hand-deployed images with the verified staging digest.
6. **Before production opens**: delete `staff.dev`, `practitioner.dev`, `patient.dev` from the production realm
   (destructive: ask), split the 5 unseal keys between 3+ people, delete `Docs/Server Keys/MedLink-Prod app key.txt`.
7. **Proposed, not done**: a database backup just before each rollout in `.github/actions/deploy-compose`; memory on
   the 1 GB hosts with MinIO added (watch `docker stats` after the first staging deploy).

### Files on Mohamed's machine (never in git)

- `Docs/Server Keys/`: EC2 keys (`api-cle.pem`, `front-cle.pem`, `vault-cle.pem`, `staging clee.pem`), `deploy-keys/`,
  `vault-init-KEEP-PRIVATE.json` (5 unseal keys; its root token is revoked), `MedLink-Prod app key.txt`.
- `Docs/Deployment/`: the PDF reference (+ `source/` HTML, print with headless Edge `--print-to-pdf`), the owner
  message PDF, `known_hosts` (4 pinned host keys), `new-setup-token.sh`, and the admin scripts `invite-admin.sh`,
  `activate-account.sh`, `promote-admin.sh`, `create-admin.sh` (all read secrets on the host, print none).

**Shelved by the repo owner:** the S3 bucket (off-host backups and Vault snapshots), and Terraform after it.

## Rules for this work

- Never handle a password, token, private key or unseal key in a chat or a file; generated secrets stay on the vault
  host; a person types or pipes their own.
- SSH as `ubuntu` with the EC2 keys, or as `deploy` with the deploy keys; check host keys against `servers.md`.
- Nothing destructive on a host without asking Mohamed first. Host scripts are tested on a throwaway container first.
- Red test commit, then green; both test orders before every push, and make the push conditional on their exit codes.

## Pitfalls met (Windows, Git Bash)

- Docker from Git Bash: `MSYS_NO_PATHCONV=1 docker ...`, mount with `$(pwd -W)`. Same for `gh variable set` with a
  value starting with `/` (Git Bash turned `/opt/medlink/api` into `C:/Program Files/Git/opt/...`).
- Never call `python` in Git Bash (Store alias hangs); use `node` or `perl`. No `jq` locally: run such tests in a container.
- Piping a script to ssh from PowerShell adds a BOM: use Git Bash, `ssh ... 'bash -s' < file`.
- `curl -K -` takes stdin for its config: put the JSON body in the same config (`data = "..."`), not in a second pipe.
- `tr` does not understand `\x27`: strip `.env` quotes with `sed -n "s/^KEY=.\(.*\).$/\1/p"`.
- Never print access logs with query strings: an activation token leaked once (cancelled by a resend).
- A privacy hook refuses commands that hold Mohamed's personal email; let him run those (`!` or an env variable).
- Flaky tests seen under memory pressure: `QrCodesTest` (reversed order), `EmailTokenServiceTest` (24 h + 300 ns): rerun.
- Claude Code kills background jobs when RAM runs out: run the suite with about 3 GB free.
- YouTrack: create tickets with `/newIssue?project=MLink&summary=...&description=...`; the command dialog
  (Ctrl+Alt+J) changes states; post plain comments in the box under the activity list; check the result by reloading.
