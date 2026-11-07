# Sprint 2 — how a session works

Sprint 2 builds identity and security for MedLink: registration, email verification, login,
staff code (MFA), sessions, passwords, account management and role-based access.
It is split into **five sub-sprints**. Each one is sized to fit **one working session** and
ends with a pull request, so a new session (person or AI assistant) can start with no other
context than these files.

| File | What it holds |
|---|---|
| [`plan.md`](plan.md) | Design rules, the three flows, the **endpoint contracts**, data changes, the sub-sprints and their YouTrack tasks |
| [`report.md`](report.md) | What is done, with proof, decisions and limits; **which sub-sprint is next** |
| this file | How to run a session |

## The session loop

1. **Know where we are.** Read `report.md` (status table at the top), then the section of the
   next sub-sprint in `plan.md`, then its YouTrack tasks (project `MLink`, sprint `sprint2`).
   If the previous sub-sprint's PR is merged, move its *To Verify* tasks to *Done*.
2. **Understand the endpoints before code.** Re-read the contracts of the sub-sprint. If one is
   unclear or wrong, fix it in `plan.md` first (same PR) and say why in `report.md`.
3. **Check the worktree, then prepare the branch.** Before any work, all of these must hold;
   if one does not, stop and sort it out first:
   - `git status --porcelain` is empty (nothing modified, staged or untracked);
   - after `git fetch --prune`, `git status -sb` shows no commits ahead or behind;
   - `git stash list` is empty, or every stash is known;
   - `git branch -vv` shows no branch `ahead` of its remote or tracking a `gone` one
     (merged or not, decide before deleting).

   Then `git checkout master && git pull --ff-only && git checkout -b feat/ss<N>-<slug>`.
   Run the setup script once (`scripts/setup-dev.ps1` or `scripts/setup-dev.sh`); it must end
   with 0 failures.
4. **Plan the work** in a few lines: files, migrations, realm changes, tests. Move **each**
   YouTrack task to *In Progress* right before starting it, not the whole sub-sprint at once.
   Only tasks built in this repository; React tasks belong to their owner.
5. **Test first.** Write the tests from the contract (status codes, bodies, the wrong-role
   case), watch them fail, then write the code.
6. **Verify for real.** `./mvnw -B verify` (JDK 17, Docker running) and the setup script.
   Try the endpoints by hand once (curl or Scalar API docs at `http://localhost:8080/scalar`).
7. **Commit per task** (YouTrack id in the message), push, open the PR to `master`.
8. **Report.** In the same PR: update `report.md` (status, PR link, proof, decisions, limits,
   next step) and move the YouTrack tasks to *To Verify* with a link to the PR.

A sub-sprint is **done** when its PR is merged and `report.md` says so; its tasks then move
to *Done*. A task that review sends back returns to *In Progress*.

YouTrack states in short: *Open* → *In Progress* (work started) → *To Verify* (PR open) →
*Done* (PR merged).

## Rules that apply to every sub-sprint

See [`CLAUDE.md`](../../../CLAUDE.md) at the repository root. In short: the frontend never
talks to Keycloak; the browser holds a session cookie only; one id per person (Keycloak's);
controller maps, service never sees a DTO; Flyway only; secrets from env; a test proves
each task.

## Known traps

- **Test classes run in another order on CI.** Windows runs them alphabetically, Linux (CI) does not,
  and classes with the same annotations share one Spring context. Before a PR, run the suite once with
  `./mvnw -B test -Dsurefire.runOrder=reversealphabetical`. Example: MockMvc's `csrf()` helper swaps
  the CSRF repository of the shared filter for a session-based one, so a cookie check in a MockMvc
  class broke only on CI. Test cookies and headers through a real port (`integration/` tests).
- **Realm file is imported only into an empty Keycloak.** Change `keycloak/realm-medlink.json`,
  then re-run the setup script: it detects the change, backs up the current realm to
  `.dev/realm-backups/` and re-imports. A change made only in the Keycloak console is not shared.
- **Migrations:** take the next free `V<N>` at merge time; renumber if someone merged first.
  Never edit a merged migration.
- **JDK:** `./mvnw` needs JDK 17+. The setup script finds one by itself; your own shell may not.
- **Docker** must be running for the tests (Testcontainers) and the setup script. If it is not
  available, push anyway: the PR's `build-test` check runs every database test. Say so in the
  report.
- **Spring Boot 4** moved test classes (`AutoConfigureMockMvc`, `TestRestTemplate`); never
  downgrade Boot to make examples compile.
- **Setup scripts** exist twice (Windows / macOS-Linux). Change both in the same PR.
- **Four security checks** (dependency-check, gitleaks on PRs, Trivy, CodeQL) may be red on
  `master` itself because of workflow permissions and a missing key. They belong to the
  repository owner; mention them, do not "fix" them in a feature PR.
