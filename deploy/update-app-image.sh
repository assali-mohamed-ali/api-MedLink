#!/bin/sh
# Updates APP_IMAGE in a server .env, for an image-only deploy when Vault cannot be read.
# The other lines (the secrets) are kept exactly as they are on the server.
# Usage: update-app-image.sh <deploy-dir> <image>
set -eu

[ "$#" -eq 2 ] || { echo "usage: update-app-image.sh <deploy-dir> <image>" >&2; exit 2; }
dir=$1
image=$2

[ -n "$image" ] || { echo "update-app-image: the image reference is empty" >&2; exit 2; }
case "$image" in
    *"'"*) echo "update-app-image: the image reference holds a single quote" >&2; exit 2 ;;
esac
[ "$(printf '%s' "$image" | tr -d '\n')" = "$image" ] ||
    { echo "update-app-image: the image reference holds a line break" >&2; exit 2; }

env_file="$dir/.env"
[ -s "$env_file" ] || { echo "update-app-image: $env_file is missing or empty" >&2; exit 1; }

tmp="$env_file.new.$$"
trap 'rm -f "$tmp"' EXIT HUP INT TERM
awk -v img="$image" -v q="'" '
    BEGIN { found = 0 }
    /^APP_IMAGE=/ { print "APP_IMAGE=" q img q; found = 1; next }
    { print }
    END { if (!found) print "APP_IMAGE=" q img q }
' "$env_file" > "$tmp"
chmod 600 "$tmp"
mv "$tmp" "$env_file"
printf 'APP_IMAGE updated to %s\n' "$image"
