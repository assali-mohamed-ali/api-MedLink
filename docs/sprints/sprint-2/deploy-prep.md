# Staging and production preparation — plan and hand-over

Tickets MLink-168 to MLink-173 (YouTrack, assigned to Mohamed, subtasks of MLink-5). Branch `chore/vault-deploy`
(api-MedLink, from `master`) for MLink-168, 169, 171, 172; `chore/cd-deploy` in `medlink-dashboard` for MLink-170.
Background, design and test results: the section "Staging and production preparation" of `report.md`. Runbooks:
`docs/operations/vault.md`, `docs/operations/servers.md`.

## Hand-over for the next session (2026-11-19, night)

**Read first:** this file, then `report.md` (Status + that section), then the two runbooks.

**What runs today**

| Host | State |
|---|---|
| vault `56.228.74.131` | Vault 1.21.0 in a container, initialised, unsealed, configured, secrets seeded for staging and prod. `https://56.228.74.131.sslip.io` |
| frontend-medlink `56.228.94.12` | Dashboard live on HTTPS (image from `master` `72a61af`). `/api` answers 502 until blocker 1 is fixed |
| api-medlink `13.50.114.144` | API, Keycloak and both databases healthy (image from `master` `0be7983`), `/readyz` 200 on the private address |
| staging-server `13.51.30.39` | Prepared, Postgres only: the 8 GB disk cannot hold Keycloak (blocker 2) |

**Blockers, in order**

1. **Repo owner, AWS console:** on the security group of `api-medlink` (`launch-wizard-1`), allow inbound TCP 8080 from `172.31.40.60/32`. Packets from the front host never reach the API host today (port 22 passes). Then `https://56.228.94.12.sslip.io/readyz` must answer 200.
2. **Repo owner, AWS console:** enlarge the staging volume to 20 GB. Then `docs/operations/servers.md`, "Grow the staging disk", and deploy the API and the front to staging.
3. **Mohamed, MLink-172: Gmail refuses the app password.** On 2026-11-19 05:18 UTC the value from `Docs/Server Keys/MedLink-Prod app key.txt` (16 letters) went into `medlink/staging/api` and `medlink/prod/api` (over SSH stdin to `vault kv patch`, never displayed) and production was redeployed. Both API hosts reach `smtp.gmail.com:587`, but a login test from the production host (curl, login and NOOP only, no mail sent) gets `535 5.7.8 Username and Password not accepted` for `medliq2026@gmail.com`. A second key (05:54 UTC) is refused the same way, with AUTH PLAIN and AUTH LOGIN, and its value is the same in the file, Vault, the host's `.env` and the running container (sha256 compared, values never shown). So Google itself refuses it: check that the app password was made **while signed in as medliq2026@gmail.com** (myaccount.google.com/apppasswords), that 2-step verification is still on (turning it off revokes every app password), and whether Security → "Recent security activity" shows a blocked sign-in from Stockholm (AWS eu-north-1) to approve. Then the same step again and the login test (read the value with `sed -n "s/^MAIL_PASSWORD=.\(.*\).$/\1/p" .env`; `tr` does not understand `\x27`). To change it: `seed-env.sh` from a terminal (`ssh -t`) only adds missing keys, so use `vault kv patch medlink/<env>/api MAIL_PASSWORD=-` with a fresh token (`vault operator generate-root`; the setup token expires 2026-11-19 08:00 UTC).
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
5. **Repo owner:** review and merge the PR of `chore/vault-deploy` and `medlink-dashboard` PR #20. The branch he created in the dashboard is `Prod`, but its workflow deploys from `prod` (names are case-sensitive).

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
