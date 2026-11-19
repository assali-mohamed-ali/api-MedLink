# Staging and production preparation — plan and hand-over

Tickets MLink-168 to MLink-173 (YouTrack, assigned to Mohamed, subtasks of MLink-5). Branch `chore/vault-deploy`
(api-MedLink, from `master`) for MLink-168, 169, 171, 172; `chore/cd-deploy` in `medlink-dashboard` for MLink-170.
Background, design and test results: the section "Staging and production preparation" of `report.md`. Runbooks:
`docs/operations/vault.md`, `docs/operations/servers.md`.

## Hand-over for the next session (2026-11-19, 11:00 UTC)

**Read first:** this file, then `report.md` (Status + that section), then the two runbooks. The full reference with
diagrams, every address and where each secret lives is a PDF on Mohamed's machine:
`Docs/Deployment/MedLink-Infrastructure-and-Deployment-Reference.pdf` (not in git: it maps the whole infrastructure).

**What runs today**

| Host | State |
|---|---|
| vault `56.228.74.131` | Vault 1.21.0 in a container, unsealed, secrets seeded for staging and prod. Roles still the old ones (sub only) until step A |
| frontend-medlink `56.228.94.12` | **Production works end to end**: dashboard and `https://56.228.94.12.sslip.io/readyz` 200 since the repo owner opened 8080 from 172.31.40.60 (2026-11-19 ~10:20 UTC) |
| api-medlink `13.50.114.144` | API, Keycloak and both databases healthy (image `master` `0be7983`); mail through Gmail works (sign-in 235; a test mail to Mohamed accepted for delivery, 250) |
| staging-server `13.51.30.39` | Disk 40 GB (repo owner, ~10:20 UTC; Ubuntu grew the partition at boot). Dashboard live (`/`, `/healthz` 200); Postgres up; **API and Keycloak not yet deployed**: step A |

**PR #130 review (repo owner, changes requested), all three fixed on the branch:**

1. CI `check-yaml` rejected Compose's `!reset`: the hook skips `deploy/docker-compose.server.yml`, which `render-env.test.sh` validates with `docker compose config` (`026cf2f`). CI green.
2. A manual staging dispatch from `master`/`main` built and published but did not deploy: `deploy-staging` now accepts the same refs (red `59c4bde`, green `5dbd332`).
3. The Vault roles bound only `sub`: they now also require the CD workflow (`workflow_ref`), its refs and `event_name` push or manual run; 7 new refusals and 2 allowed cases in `vault/test/vault.test.sh` (red `a790d8e`, green `ca6f7c5`). **Applied in git only**: the live Vault gets it in step A.

**Next, in order**

A. **Mohamed, a Vault setup token** (the last one expired at 08:00 UTC; Claude Code's permission check refused making
   one from the unseal keys). Either way the keys are typed or piped, never shown, never stored on the host:
   - by hand, three key holders, on the vault host (`ssh -t`): `docker exec -it medlink-vault vault operator generate-root -init`,
     then `docker exec -it medlink-vault vault operator generate-root -nonce=<nonce>` three times (key at the hidden
     prompt), then `-decode=<encoded> -otp=<otp>`; with that root token:
     `vault token create -policy=medlink-setup -ttl=3h -orphan -field=token > /root/medlink-setup.token` and
     `vault token revoke -self`.
   Then Claude: run `vault/setup.sh` again (new roles), deploy the API and Keycloak to staging, a real signup on staging
   with its verification mail (MLink-172), end-to-end checks of both environments (MLink-173), revoke the setup token.
B. **Mohamed, GitHub environment values**: the commands in item 4 below.
B2. **First admin of the app (production):** there is no admin in production except the dev user `staff.dev` (to be
   deleted). `Docs/Deployment/create-admin.sh` (Mohamed's machine, not in git) creates one in Keycloak through the API's
   own service account `medlink-admin`: group `/administrative-staff`, email verified, a temporary password typed by
   the person at a hidden prompt. At the first login the app makes them choose a password, then set up the staff code;
   the API creates the database row then (GitHub #29). `--check <email>` tests the connection and creates nothing
   (tested 2026-11-19: OK). Run it in Git Bash yourself: `bash create-admin.sh <email> <first name> <last name>`.
C. **Repo owner:** review PR #130 again and merge it with medlink-dashboard PR #20. The dashboard branch is now `prod`.
   Give the `production` environments a deployment rule (API: tags `v*`; dashboard: `prod` and `v*`).

**Details of each item (1 to 3 done, 4 and 5 open)**

1. Security group: 8080 from `172.31.40.60/32` on `api-medlink` (repo owner, 2026-11-19).
2. Staging volume enlarged to 40 GB (repo owner, 2026-11-19).
3. **Mail, done on production, 2026-11-19 (MLink-172):** the Gmail app password (from Mohamed's local file `Docs/Server Keys/MedLink-Prod app key.txt`) is in `medlink/staging/api` and `medlink/prod/api`, sent over SSH stdin to `vault kv patch`, never displayed. The account is **`medlinq2026@gmail.com`**: the settings files first said `medliq2026`, and Gmail answered `535 Username and Password not accepted` to every key; `deploy/test/render-env.test.sh` now guards the address. Login test from the production host (curl, login and NOOP only, no mail sent): `235 2.7.0 Accepted`. Still to check: a real signup on staging delivers its mail, once staging runs. Login test, reading the value from the host's `.env`: strip its quotes with `sed -n "s/^MAIL_PASSWORD=.\(.*\).$/\1/p" .env` (`tr` does not understand `\x27`), write a `--netrc-file` with umask 077, `curl --ssl-reqd --url smtp://smtp.gmail.com:587 -X NOOP`. To change the password: `seed-env.sh` from a terminal (`ssh -t`) only adds missing keys, so use `vault kv patch medlink/<env>/api MAIL_PASSWORD=-` with a fresh token (`vault operator generate-root`; the setup token expires 2026-11-19 08:00 UTC).
4. **Mohamed:** the GitHub environment values, needed by CD (Claude Code's permission check refused uploading the private deploy key). From `code/api-MedLink`, in Git Bash:
   ```sh
   K="../../Docs/Server Keys/deploy-keys"
   R=assali-mohamed-ali/api-MedLink
   for e in staging production; do gh secret set DEPLOY_SSH_KEY -R $R --env $e < "$K/medlink-deploy-$e"; gh variable set DEPLOY_USER -R $R --env $e --body deploy; gh variable set DEPLOY_PATH -R $R --env $e --body /opt/medlink/api; done
   gh variable set DEPLOY_HOST -R $R --env staging --body 13.51.30.39
   gh variable set DEPLOY_HEALTH_URL -R $R --env staging --body https://13.51.30.39.sslip.io/readyz
   gh variable set DEPLOY_HOST -R $R --env production --body 13.50.114.144
   gh variable set DEPLOY_HEALTH_URL -R $R --env production --body https://56.228.94.12.sslip.io/readyz
   ssh-keyscan -t ed25519 13.51.30.39 | gh secret set DEPLOY_KNOWN_HOSTS -R $R --env staging
   ssh-keyscan -t ed25519 13.50.114.144 | gh secret set DEPLOY_KNOWN_HOSTS -R $R --env production
   ```
   Check each scanned key against the fingerprints in `servers.md`. The dashboard repository needs the same names with path `/opt/medlink/front`, health URL `.../healthz`, and host `56.228.94.12` for production.
5. **Repo owner:** review and merge PR #130 (`chore/vault-deploy`) and `medlink-dashboard` PR #20. Dashboard branch renamed `Prod` → `prod` (done, 2026-11-19).

**Before production opens:** delete the dev users `staff.dev`, `practitioner.dev`, `patient.dev` from the production Keycloak (MLink-173), and split the five unseal keys between at least three people.

**Where the keys are (on Mohamed's machine only, never in git):** `Docs/Server Keys/vault-init-KEEP-PRIVATE.json` (the five unseal keys; the root token in it is already revoked) and `Docs/Server Keys/deploy-keys/`.

**Shelved by the repo owner:** the S3 bucket, so backups and Vault snapshots stay on their hosts for now. Terraform waits for that bucket.

**The first deploys were done by hand** over SSH with the deploy keys, with the same steps as `.github/actions/deploy-compose`, because CD runs only after the merge.

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
