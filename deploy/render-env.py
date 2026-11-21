#!/usr/bin/env python3
"""Writes a server's .env from the settings in the repository and the secrets read from Vault (MLink-169).

    render-env.py env <settings-file> < secrets.json > .env
    render-env.py backup < secrets.json > .backup.env

secrets.json is the flat object stored at medlink/<env>/api. A value is never printed on an error, only its key.
"""
import json
import sys

REQUIRED = (
    "POSTGRES_PASSWORD", "APP_DB_PASSWORD", "KEYCLOAK_DB_PASSWORD", "KC_BFF_CLIENT_SECRET", "KC_ADMIN_CLIENT_SECRET",
    "KEYCLOAK_ADMIN_PASSWORD", "KC_DEV_USERS_PASSWORD", "APP_MFA_ENCRYPTION_KEY", "APP_REPORT_ENCRYPTION_KEY",
)
BACKUP_KEY = "BACKUP_PASSPHRASE"
OPTIONAL = ("MAIL_PASSWORD",)  # typed by a person (seed-env.sh): the API starts without it, mail does not leave


def die(message):
    print(f"render-env: {message}", file=sys.stderr)
    sys.exit(2)


def line(key, value):
    if not isinstance(value, str) or "'" in value or "\n" in value or "\r" in value:
        die(f"{key}: the value holds a quote or a line break, or is not text")
    return f"{key}='{value}'"


def main(argv):
    if len(argv) < 2 or argv[1] not in ("env", "backup") or (argv[1] == "env" and len(argv) != 3):
        die("usage: render-env.py env <settings-file> | render-env.py backup")
    try:
        secrets = json.load(sys.stdin)
    except ValueError:
        die("the secrets are not valid JSON")
    if not isinstance(secrets, dict):
        die("the secrets must be one JSON object")

    if argv[1] == "backup":
        if not secrets.get(BACKUP_KEY):
            die(f"{BACKUP_KEY} is missing")
        print(line(BACKUP_KEY, secrets[BACKUP_KEY]))
        return

    missing = [key for key in REQUIRED if not secrets.get(key)]
    if missing:
        die("missing from Vault: " + " ".join(missing))
    with open(argv[2], encoding="utf-8") as handle:
        settings = handle.read()
    if not any(row.startswith("SPRING_PROFILES_ACTIVE=") for row in settings.splitlines()):
        die("the settings file must define SPRING_PROFILES_ACTIVE")

    output = [settings.rstrip("\n")]
    output += [line(key, secrets[key]) for key in REQUIRED]
    for key in OPTIONAL:
        if secrets.get(key):
            output.append(line(key, secrets[key]))
        else:
            print(f"render-env: warning: {key} is not in Vault yet, the API cannot send mail", file=sys.stderr)
    print("\n".join(output))


if __name__ == "__main__":
    main(sys.argv)
