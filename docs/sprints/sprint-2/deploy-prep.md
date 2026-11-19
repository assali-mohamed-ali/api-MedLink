# Staging and production preparation — plan and hand-over

Tickets MLink-168 to MLink-173 (YouTrack, assigned to Mohamed, subtasks of MLink-5). Branch `chore/vault-deploy`
(api-MedLink) for MLink-168, 169, 171, 172, 173; `chore/cd-deploy` in `medlink-dashboard` (merged, #20) for MLink-170.
Background and test results: `report.md`, section "Staging and production preparation". Runbooks:
`docs/operations/vault.md`, `docs/operations/servers.md`, `docs/DEPLOYMENT.md`.

## Hand-over for the next session (2026-11-19, 20:00 UTC)

**Start every session with:** GitHub (new commits on `master`/`staging`/`prod`, PRs, reviews, CI) and YouTrack (new
comments on MLink-168..173 and the tickets touching accounts), then `git fetch` + merge `origin/master`, and free RAM
(about 3 GB before the test suite). The full reference with diagrams, every address and where each secret lives is a
PDF on Mohamed's machine, not in git: `Docs/Deployment/MedLink-Infrastructure-and-Deployment-Reference.pdf`.

**Two rules from Mohamed:** deploys go **only through the CD workflows**, never by hand over SSH (host preparation,
Vault configuration and diagnostics over SSH are fine). Never handle a password, private key or unseal key in chat.

### Where things stand

| Part | State |
|---|---|
| Production (api-medlink `13.50.114.144` + frontend-medlink `56.228.94.12`) | Works end to end: dashboard, API through the front (`/readyz` 200), Gmail mail (account `medlinq2026@gmail.com`). Images `medlink-api:0be7983`, `medlink-front:72a61af`, deployed by hand on 2026-11-19 (before the workflow-only rule) |
| Staging (`13.51.30.39`, disk 40 GB) | Dashboard live, Postgres up; API and Keycloak not deployed: CD will do it on the first push to `staging` |
| Vault (`56.228.74.131`) | Unsealed, secrets seeded. Live roles are still #130's (`sub` only); #133's stricter roles apply when `setup.sh` runs again (step 2 below) |
| First admin (production) | Invited on Mohamed's school address (no password; API activation mail). The dashboard's `/activer-compte` is a placeholder, so activation uses `Docs/Deployment/activate-account.sh` with the newest mail (17:23 UTC, valid to 2026-11-20 17:23 UTC; a new one: `POST /api/auth/account/activate/resend`) |
| Specialties | None in staging or production (only the dev seed has them): an admin cannot create a practitioner yet |

| Git | State |
|---|---|
| api-MedLink `master` | Has #130 (Vault deploys), the owner's #132 (production deploys automatically from the verified staging run unless `AUTO_DEPLOY_PROD=false`) and #131 (MLink-37) |
| PR #133 (`chore/vault-deploy` → `master`) | The 3 review fixes of #130 + production Vault roles that accept `staging` (needed by #132). CI all green incl. AI disclosure; both orders 847/0; **waiting for the owner's review**. One local docs commit not pushed yet |
| medlink-dashboard `master` | Has #18 (appointments epic), #20, #21 (auto prod deploy). Activation page (MLink-20, Chourouk) only on `epic/MLink-11` (Ahlem's commit 17/11), no PR, 56 ahead / 20 behind |
| `staging`, `prod` (both repos) | Far behind `master` (api `staging` 270+ commits). Every branch needs the owner's review (CODEOWNERS `*`); Mohamed has push, not admin |

GitHub environments (both repos, `staging` and `production`): `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PATH`,
`DEPLOY_HEALTH_URL`, `DEPLOY_KNOWN_HOSTS` set on 2026-11-19. `DEPLOY_SSH_KEY`: Mohamed sets it (command in the chat;
check with `gh api repos/assali-mohamed-ali/<repo>/environments/<env>/secrets`). The owner received a message listing
what needs him: review #133, `master` → `staging` sync, `AUTO_DEPLOY_PROD`, production deployment rules, the unused
`DEPLOY_ENV_FILE` secret, `CROSS_REPO_TOKEN`, the activation page PR, the nightly gitleaks finding on his branch
`fix/workflow-security-and-reliability`, dev users and unseal keys.

YouTrack: MLink-168, 169, 170, 171 *À vérifier*; 172, 173 *En cours*; comments posted on 168, 169 (#133) and on MLink-20.

### Next, in order

1. **Check and sync** (above). If #133 is merged, merge `master` into the working branch first.
2. **Vault** (needs Mohamed's setup token; the permission check refuses Claude reading the unseal keys). Mohamed runs,
   from Git Bash, `Docs/Deployment/new-setup-token.sh` fed with three unseal keys (the exact one-liner is in the chat
   of 2026-11-19; by hand: `generate-root` steps in `vault.md`). It writes a 3-hour `/root/medlink-setup.token` and
   revokes the root token. Then: copy `vault/` to the host, `setup.sh` (new roles), check one role with
   `vault read auth/jwt-github/role/api-production`, revoke the setup token. `seed-front.sh` needs the private deploy
   key on the vault host: skip it, the dashboard falls back to its GitHub secrets.
3. **Specialties (recommended: a migration)**: new ticket, `V19__specialties_reference_data.sql` like V15 (same ids as
   the dev seed, `ON CONFLICT DO NOTHING`), red test first, PR to `master`. List: the 3 dev specialties unless Mohamed
   gives the real one.
4. **Whole-app integration branch (Mohamed's request)**: `integration/whole-app` from `master` in each repo, merge the
   other branches, resolve, both test orders green, then decide with Mohamed and the owner whether it can go to
   `master`. Care: most open PRs have *changes requested* (#128 epic/MLink-8, #127, #122, #121, #90, dependabot #115
   #114 #110 #75); teammates' code stays theirs (comment on their tickets, do not rewrite it); `DEPLOYMENT.md` warns
   against blindly merging the legacy `staging`/`prod` differences. First list every remote branch with ahead/behind
   and its PR/review state, skip merged or stale ones, merge in dependency order, one merge commit per branch.
5. **Deploy through CD only**: when `master` has what must ship, the owner merges a `master` → `staging` sync in both
   repos; CD deploys staging, smoke-tests, waits for the other repository, then deploys the same digest to production.
   Then check: a real signup on staging receives its mail (MLink-172), the admin logs in, both environments pass the
   checks of the PDF (MLink-173).
6. **Before production opens**: delete `staff.dev`, `practitioner.dev`, `patient.dev` from the production realm
   (destructive: ask), split the 5 unseal keys between 3+ people, delete `Docs/Server Keys/MedLink-Prod app key.txt`.
7. **Proposed, not done**: a database backup just before each rollout in `.github/actions/deploy-compose`.

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
