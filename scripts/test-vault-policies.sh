#!/usr/bin/env bash
# Rehearses docker-compose.prod.yml's vault-init against a throwaway Vault (cashu-mint#550).
# Never touches a shared Vault: it starts hashicorp/vault:1.18 in dev mode under a private
# compose project, runs the real vault-init service twice, then checks, as each client's AppRole
# (read from the files vault-init wrote):
#   - cashu-vault-jpa reads keys and cannot write, delete or list
#   - cashu-mint-rest and cashu-mint-admin read, create and update keys, and cannot delete or list
#   - only cashu-mint-admin reads the keyset seed; nobody, not even it, can overwrite the seed
#   - no client reads another seeds/ path, sys/, or another client's credential
#   - a second vault-init run rotates the secret-ids (the old one no longer logs in)
#   - the old shared cashu-vault policy and role are gone
#
#   scripts/test-vault-policies.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
P=vault550-$$-$RANDOM
T=$(mktemp -d)
ROOT_TOKEN=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
fail=0; pass=0
ok()  { pass=$((pass + 1)); echo "ok   $1"; }
bad() { fail=$((fail + 1)); echo "FAIL $1"; }

# The prod compose's own vault-init and volumes; Vault swapped for a dev-mode server.
cat > "$T/override.yml" <<EOF
services:
  hashicorp-vault:
    command: ["vault", "server", "-dev", "-dev-listen-address=0.0.0.0:8200"]
    environment:
      VAULT_DEV_ROOT_TOKEN_ID: "$ROOT_TOKEN"
    volumes: !reset []
  reader:
    image: hashicorp/vault:1.18
    entrypoint: ["sleep", "600"]
    volumes: ["vault-approle:/approle:ro"]
    networks: [cashu]
networks:
  cashu:
    name: $P-cashu
EOF
cat > "$T/env" <<EOF
VAULT_INIT_TOKEN=$ROOT_TOKEN
VAULT_DB_PASSWORD=x
VAULT_API_TOKEN=x
MINT_DB_PASSWORD=x
MINT_WEBHOOK_SECRET=x
MINT_ADMIN_PASSWORD=x
ADMIN_DB_PASSWORD=x
GATEWAY_DB_PASSWORD=x
PAYMENT_ADAPTER_DB_PASSWORD=x
EOF
dc() { docker compose -p "$P" --env-file "$T/env" -f "$ROOT/docker-compose.prod.yml" -f "$T/override.yml" "$@"; }
for _ in $(seq 20); do
  m=$(dc config -q 2>&1 | grep -o 'set [A-Z_]*' | head -1 | cut -d' ' -f2); [ -z "$m" ] && break; echo "$m=x" >> "$T/env"
done
cleanup() { [ -s "$T/err" ] && { echo "--- vault-init stderr"; tail -5 "$T/err"; }; dc down -v --remove-orphans >/dev/null 2>&1; rm -rf "$T"; }
trap cleanup EXIT

dc up -d --wait hashicorp-vault reader >/dev/null 2>&1 || { echo "vault did not start"; exit 2; }
vx() { dc exec -T -e VAULT_ADDR=http://127.0.0.1:8200 -e "VAULT_TOKEN=$ROOT_TOKEN" hashicorp-vault "$@"; }
for _ in $(seq 30); do vx vault status >/dev/null 2>&1 && break; sleep 1; done
# A pre-#550 Vault: the shared policy and role exist and must be removed.
printf 'path "cashu/data/seeds/*" { capabilities = ["create","read","update"] }\n' | vx vault policy write cashu-vault - >/dev/null
vx vault auth enable approle >/dev/null
vx vault write auth/approle/role/cashu-vault token_policies=cashu-vault >/dev/null

out=$(dc run --rm vault-init 2>"$T/err") && ok "vault-init run 1" || bad "vault-init run 1: $out"
printf '%s' "$out" > "$T/log"
vx vault policy read cashu-vault >/dev/null 2>&1 && bad "the shared cashu-vault policy is still there" || ok "the shared cashu-vault policy is removed"
vx vault read auth/approle/role/cashu-vault >/dev/null 2>&1 && bad "the shared cashu-vault role is still there" || ok "the shared cashu-vault role is removed"
vx vault kv metadata get -mount=cashu -format=json seeds/mint-admin/keyset-master-seed | tr -d ' \n' | grep -q '"cas_required":true' \
  && ok "cas_required on the keyset seed" || bad "cas_required missing"

vx vault kv put -mount=cashu seeds/other seed=x >/dev/null
vx vault kv put -mount=cashu keys/m/k/1 private_key=aa >/dev/null

rd() { dc exec -T reader cat "/approle/$1/vault.hashi.auth.approle.$2"; }
login() {
  local rid sid; rid=$(rd "$1" role-id) sid=$(rd "$1" secret-id)
  dc exec -T -e VAULT_ADDR=http://hashicorp-vault:8200 reader \
    vault write -field=token auth/approle/login role_id="$rid" secret_id="$sid"
}
as() { # role want what cmd
  local r=$1 want=$2 what=$3; shift 3
  local t rc=0; t=$(login "$r" 2>/dev/null) || { bad "$r: login failed"; return; }
  dc exec -T -e VAULT_ADDR=http://hashicorp-vault:8200 -e "VAULT_TOKEN=$t" reader sh -c "$*" >/dev/null 2>&1 || rc=$?
  local got=allow; [ "$rc" = 0 ] || got=deny
  [ "$got" = "$want" ] && ok "$r: $what ($want)" || bad "$r: $what (got $got, want $want)"
}
S=seeds/mint-admin/keyset-master-seed
# Before the operator creates the seed, mint-admin cannot create it either (read-only grant).
as cashu-mint-admin deny "create the keyset seed (CAS 0)" \
  "echo '{\"options\":{\"cas\":0},\"data\":{\"seed\":\"ffff\"}}' | vault write cashu/data/$S -"
vx vault kv put -mount=cashu -cas=0 $S seed=0001 >/dev/null
for r in cashu-vault-jpa cashu-mint-rest cashu-mint-admin; do
  as $r allow "read a key" "vault kv get -mount=cashu keys/m/k/1"
  if [ $r = cashu-vault-jpa ]; then
    as $r deny "create a key" "vault kv put -mount=cashu keys/m/k/2 private_key=x"
  else
    as $r allow "create a key" "vault kv put -mount=cashu keys/$r/k/1 private_key=x"
    as $r allow "update a key with CAS" "vault kv put -mount=cashu -cas=1 keys/$r/k/1 private_key=y"
  fi
  as $r deny "delete a key" "vault kv delete -mount=cashu keys/m/k/1"
  as $r deny "destroy a key version" "vault kv destroy -mount=cashu -versions=1 keys/m/k/1"
  as $r deny "list the mount" "vault kv list -mount=cashu keys"
  as $r deny "read seeds/other" "vault kv get -mount=cashu seeds/other"
  as $r deny "overwrite the seed (CAS 1)" "vault kv put -mount=cashu -cas=1 $S seed=ffff"
  as $r deny "overwrite the seed (no CAS)" "vault kv put -mount=cashu $S seed=ffff"
  as $r deny "read sys/mounts" "vault secrets list"
  if [ $r = cashu-mint-admin ]; then
    as $r allow "read its keyset seed" "vault kv get -mount=cashu $S"
  else
    as $r deny "read the keyset seed" "vault kv get -mount=cashu $S"
  fi
done
[ "$(vx vault kv get -mount=cashu -field=seed $S)" = 0001 ] && ok "the seed is unchanged" || bad "the seed changed"

old_rid=$(rd cashu-mint-rest role-id) old_sid=$(rd cashu-mint-rest secret-id)
out=$(dc run --rm vault-init 2>"$T/err") && ok "vault-init run 2 (idempotent)" || bad "vault-init run 2: $out"
printf '%s' "$out" >> "$T/log"
[ "$(rd cashu-mint-rest secret-id)" != "$old_sid" ] && ok "run 2 rotates the secret-id" || bad "run 2 kept the secret-id"
dc exec -T -e VAULT_ADDR=http://hashicorp-vault:8200 reader vault write auth/approle/login \
  role_id="$old_rid" secret_id="$old_sid" >/dev/null 2>&1 && bad "the old secret-id still logs in" || ok "the old secret-id is destroyed"
as cashu-mint-rest allow "the new secret-id logs in and reads" "vault kv get -mount=cashu keys/m/k/1"

grep -q -e "$ROOT_TOKEN" -e "$old_sid" "$T/log" && bad "vault-init printed a token or secret-id" || ok "vault-init printed no token or secret-id"
echo "passed: $pass  failed: $fail"
[ "$fail" = 0 ]
