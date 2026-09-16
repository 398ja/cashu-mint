#!/usr/bin/env bash
# Generate the mint's operator credential in the two forms it is consumed in.
#
# The mint (cashu-mint-rest) holds a bcrypt hash in MINT_ADMIN_PASSWORD; outside
# the local profile it refuses plain text. Prometheus scrapes the management
# port with the plain text, read from the file CASHU_MINT_SCRAPE_PASSWORD_FILE
# points at. This script emits both from one random password so they cannot
# drift, and escapes the hash for a compose env file, where a single `$` is
# interpolation and silently truncates the value.
#
# Usage:
#   scripts/mint-admin-password.sh [scrape-password-file]
#
# The plain text is written to the file (default ~/cashu-mint-scrape-password,
# mode 600) and never printed. The MINT_ADMIN_PASSWORD line is printed for
# pasting into .env / .env.staging.
set -euo pipefail

SCRAPE_FILE="${1:-$HOME/cashu-mint-scrape-password}"
BCRYPT_COST=10
PASSWORD_BYTES=24

generate_password() {
    openssl rand -base64 "$PASSWORD_BYTES" | tr -d '\n'
}

bcrypt_hash() {
    local password="$1"
    if python3 -c 'import bcrypt' 2>/dev/null; then
        BCRYPT_COST="$BCRYPT_COST" python3 - "$password" <<'EOF'
import bcrypt, os, sys
print(bcrypt.hashpw(sys.argv[1].encode(), bcrypt.gensalt(int(os.environ["BCRYPT_COST"]))).decode())
EOF
    elif command -v htpasswd >/dev/null 2>&1; then
        htpasswd -nbBC "$BCRYPT_COST" x "$password" | cut -d: -f2
    elif command -v docker >/dev/null 2>&1; then
        docker run --rm httpd:2.4-alpine htpasswd -nbBC "$BCRYPT_COST" x "$password" | cut -d: -f2
    else
        echo "need python3 with bcrypt, htpasswd, or docker to hash the password" >&2
        exit 1
    fi
}

escape_for_compose_env() {
    sed 's/\$/$$/g'
}

write_scrape_file() {
    local password="$1"
    install -m 600 /dev/null "$SCRAPE_FILE"
    printf '%s' "$password" > "$SCRAPE_FILE"
}

main() {
    local password hash
    password="$(generate_password)"
    hash="$(bcrypt_hash "$password" | tr -d '\n')"
    write_scrape_file "$password"

    echo "Plain text written to $SCRAPE_FILE (mode 600)."
    echo "Point CASHU_MINT_SCRAPE_PASSWORD_FILE at it when starting the observability stack."
    echo
    echo "Add to the mint's env file (.env / .env.staging):"
    echo
    printf 'MINT_ADMIN_PASSWORD={bcrypt}%s\n' "$(printf '%s' "$hash" | escape_for_compose_env)"
}

main
