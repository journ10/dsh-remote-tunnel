#!/usr/bin/env bash
# dsh-remote-tunnel — one-command relay installer for Ubuntu/Debian.
#
#   sudo bash install.sh --domain example.com --email you@example.com
#
# It writes a 0600 .env, builds the relay image, starts Caddy + relay with
# Docker Compose, and prints the DSH plugin config and DNS records.
#
# Actions: install (default), --status, --logs, --upgrade, --uninstall.
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${INSTALL_DIR:-/opt/dsh-remote-tunnel}"
ACTION="install"
DOMAIN="${DOMAIN:-}"
RELAY_HOST="${RELAY_HOST:-}"
INSTANCE_DOMAIN="${INSTANCE_DOMAIN:-}"
EMAIL="${EMAIL:-}"
INSTANCE_ID="${INSTANCE_ID:-default}"
INSTALL_DOCKER=0
PURGE=0
START=1
ROTATE_TOKEN=0

log()  { printf '\n== %s ==\n' "$*"; }
note() { printf '  %s\n' "$*"; }
warn() { printf '  WARN: %s\n' "$*" >&2; }
die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage:
  sudo bash install.sh --domain example.com --email you@example.com [options]

Options:
  --domain <base-domain>       Base domain, e.g. example.com (required)
  --email <address>            ACME account e-mail (default: admin@<domain>)
  --relay-host <host>          Relay host (default: relay.<domain>)
  --instance-domain <host>     Instance base (default: <relay-host>)
  --instance-id <id>           Bootstrap instance id (default: default)
  --dir <path>                 Install directory (default: /opt/dsh-remote-tunnel)
  --install-docker             Install Docker via get.docker.com when missing
  --rotate-token               Generate a new instance token and restart
  --no-start                   Write files and .env, do not start containers
  --status                     Show container status and relay health
  --logs                       Follow relay/Caddy logs
  --upgrade                    Rebuild and restart the stack
  --uninstall                  Stop the stack (volumes are kept)
  --purge                      With --uninstall: also delete volumes/data
  -h, --help                   Show this help

DNS (must exist before Caddy can issue certificates):
  A  relay.example.com            -> <server IPv4>
  A  *.relay.example.com          -> <server IPv4>

Ports 80/443 must be free. The relay management port is bound to 127.0.0.1:18080.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    --email) EMAIL="${2:-}"; shift 2 ;;
    --relay-host) RELAY_HOST="${2:-}"; shift 2 ;;
    --instance-domain) INSTANCE_DOMAIN="${2:-}"; shift 2 ;;
    --instance-id) INSTANCE_ID="${2:-}"; shift 2 ;;
    --dir) INSTALL_DIR="${2:-}"; shift 2 ;;
    --install-docker) INSTALL_DOCKER=1; shift ;;
    --rotate-token) ROTATE_TOKEN=1; shift ;;
    --no-start) START=0; shift ;;
    --status) ACTION="status"; shift ;;
    --logs) ACTION="logs"; shift ;;
    --upgrade) ACTION="upgrade"; shift ;;
    --uninstall) ACTION="uninstall"; shift ;;
    --purge) PURGE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (use --help)" ;;
  esac
done

[ "$(id -u)" -eq 0 ] || die "run as root: sudo bash $0 $*"

if ! command -v docker >/dev/null 2>&1; then
  if [ "$INSTALL_DOCKER" -eq 1 ]; then
    log "installing Docker"
    curl -fsSL https://get.docker.com | sh
  else
    die "docker is not installed; rerun with --install-docker or install Docker Engine + Compose plugin first"
  fi
fi

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  die "docker compose is not available; install the Docker Compose plugin"
fi

random_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
  fi
}

valid_host() {
  printf '%s' "$1" | grep -Eq '^[a-z0-9]([a-z0-9-]{0,62})(\.[a-z0-9]([a-z0-9-]{0,62}))+$'
}

env_get() {
  [ -f "$INSTALL_DIR/.env" ] || return 0
  awk -F= -v k="$1" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' "$INSTALL_DIR/.env"
}

env_set() {
  local key="$1" value="$2"
  if grep -q "^${key}=" "$INSTALL_DIR/.env"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$INSTALL_DIR/.env"
  else
    printf '%s=%s\n' "$key" "$value" >> "$INSTALL_DIR/.env"
  fi
}

compose() {
  ( cd "$INSTALL_DIR" && "${COMPOSE[@]}" "$@" )
}

sync_files() {
  [ -f "$SCRIPT_DIR/Dockerfile" ] || die "Dockerfile not found next to install.sh"
  [ -f "$SCRIPT_DIR/docker-compose.yml" ] || die "docker-compose.yml not found next to install.sh"
  [ -f "$SCRIPT_DIR/Caddyfile" ] || die "Caddyfile not found next to install.sh"
  [ -f "$SCRIPT_DIR/package.json" ] || die "package.json not found next to install.sh"
  [ -f "$SCRIPT_DIR/package-lock.json" ] || die "package-lock.json not found next to install.sh"
  case "$INSTALL_DIR" in
    /|/bin|/boot|/dev|/etc|/home|/lib|/opt|/root|/sbin|/srv|/tmp|/usr|/var)
      die "refusing to use $INSTALL_DIR as the install directory" ;;
  esac
  mkdir -p "$INSTALL_DIR"
  chmod 700 "$INSTALL_DIR"
  if [ "$SCRIPT_DIR" != "$INSTALL_DIR" ]; then
    cp -a "$SCRIPT_DIR/Dockerfile" "$SCRIPT_DIR/docker-compose.yml" "$SCRIPT_DIR/Caddyfile" "$INSTALL_DIR/"
    cp -a "$SCRIPT_DIR/package.json" "$SCRIPT_DIR/package-lock.json" "$INSTALL_DIR/"
    mkdir -p "$INSTALL_DIR/src"
    cp -a "$SCRIPT_DIR/src/." "$INSTALL_DIR/src/"
  fi
}

wait_health() {
  local i
  for i in $(seq 1 60); do
    if curl -fsS --max-time 2 http://127.0.0.1:18080/healthz >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

public_ipv4() {
  curl -4 -fsS --max-time 5 https://ifconfig.me 2>/dev/null \
    || curl -4 -fsS --max-time 5 https://api.ipify.org 2>/dev/null \
    || hostname -I 2>/dev/null | awk '{print $1}' \
    || true
}

case "$ACTION" in
  status)
    [ -d "$INSTALL_DIR" ] || die "install directory not found: $INSTALL_DIR"
    log "containers"
    compose ps
    log "relay health"
    curl -fsS http://127.0.0.1:18080/healthz || warn "relay health endpoint did not answer"
    exit 0
    ;;
  logs)
    [ -d "$INSTALL_DIR" ] || die "install directory not found: $INSTALL_DIR"
    compose logs -f --tail=100
    exit 0
    ;;
  upgrade)
    [ -d "$INSTALL_DIR" ] || die "install directory not found: $INSTALL_DIR"
    log "syncing source"
    sync_files
    log "rebuilding"
    compose up -d --build
    wait_health || warn "relay did not become healthy within 120s"
    note "upgrade complete"
    exit 0
    ;;
  uninstall)
    [ -d "$INSTALL_DIR" ] || die "install directory not found: $INSTALL_DIR"
    log "stopping"
    if [ "$PURGE" -eq 1 ]; then
      warn "purging relay data and Caddy certificates"
      compose down -v
    else
      compose down
      note "data volumes kept; use --purge to delete them"
    fi
    exit 0
    ;;
esac

# ---------------------------------------------------------------------------
# install
# ---------------------------------------------------------------------------
[ -n "$DOMAIN" ] || die "--domain is required (use --help)"
DOMAIN="$(printf '%s' "$DOMAIN" | tr '[:upper:]' '[:lower:]' | sed 's/^https\?:\/\///; s|/.*$||; s/:.*$//; s/\.$//')"
valid_host "$DOMAIN" || die "invalid --domain: $DOMAIN"

RELAY_HOST="${RELAY_HOST:-relay.$DOMAIN}"
RELAY_HOST="$(printf '%s' "$RELAY_HOST" | tr '[:upper:]' '[:lower:]')"
valid_host "$RELAY_HOST" || die "invalid relay host: $RELAY_HOST"

INSTANCE_DOMAIN="${INSTANCE_DOMAIN:-$RELAY_HOST}"
INSTANCE_DOMAIN="$(printf '%s' "$INSTANCE_DOMAIN" | tr '[:upper:]' '[:lower:]')"
valid_host "$INSTANCE_DOMAIN" || die "invalid instance domain: $INSTANCE_DOMAIN"

INSTANCE_ID="$(printf '%s' "$INSTANCE_ID" | tr '[:upper:]' '[:lower:]')"
printf '%s' "$INSTANCE_ID" | grep -Eq '^[a-z0-9][a-z0-9-]{0,31}$' || die "invalid --instance-id: $INSTANCE_ID"

EMAIL="${EMAIL:-admin@$DOMAIN}"
printf '%s' "$EMAIL" | grep -Eq '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$' || die "invalid --email: $EMAIL"

INSTANCE_HOST="$INSTANCE_ID.$INSTANCE_DOMAIN"

command -v curl >/dev/null 2>&1 || die "curl is required"
if command -v ss >/dev/null 2>&1; then
  if ss -ltn 2>/dev/null | awk '$4 ~ /:(80|443)$/ {print}' | grep -q .; then
    warn "something is already listening on 80/443; stop it before starting Caddy"
  fi
fi

log "installing to $INSTALL_DIR"
sync_files

if [ ! -f "$INSTALL_DIR/.env" ]; then
  : > "$INSTALL_DIR/.env"
  chmod 600 "$INSTALL_DIR/.env"
fi

COOKIE_SECRET="$(env_get COOKIE_SECRET)"
if [ "${#COOKIE_SECRET}" -lt 32 ]; then
  COOKIE_SECRET="$(random_hex)"
fi

TOKEN="$(env_get BOOTSTRAP_INSTANCE_TOKEN)"
if [ "${#TOKEN}" -lt 16 ] || [ "$ROTATE_TOKEN" -eq 1 ]; then
  TOKEN="$(random_hex)"
fi

env_set PUBLIC_HOST "$RELAY_HOST"
env_set INSTANCE_BASE_DOMAIN "$INSTANCE_DOMAIN"
env_set PUBLIC_URL "https://$RELAY_HOST"
env_set ACME_EMAIL "$EMAIL"
env_set COOKIE_SECRET "$COOKIE_SECRET"
env_set BOOTSTRAP_INSTANCE_ID "$INSTANCE_ID"
env_set BOOTSTRAP_INSTANCE_TOKEN "$TOKEN"
env_set BOOTSTRAP_PUBLIC_HOST "$INSTANCE_HOST"
env_set COOKIE_SECURE "true"
chmod 600 "$INSTALL_DIR/.env"

# Keep optional knobs if the operator already set them.
for key in PAIR_TTL_MS PAIR_CODE_LENGTH DEVICE_COOKIE_DAYS RATE_LIMIT_PER_MINUTE MAX_REQUEST_BYTES MAX_WS_MESSAGE_BYTES TRUST_PROXY LOG_LEVEL; do
  [ -n "$(env_get "$key")" ] || env_set "$key" "$(case "$key" in
    PAIR_TTL_MS) echo 120000 ;;
    PAIR_CODE_LENGTH) echo 10 ;;
    DEVICE_COOKIE_DAYS) echo 30 ;;
    RATE_LIMIT_PER_MINUTE) echo 30 ;;
    MAX_REQUEST_BYTES) echo 536870912 ;;
    MAX_WS_MESSAGE_BYTES) echo 8388608 ;;
    TRUST_PROXY) echo true ;;
    LOG_LEVEL) echo info ;;
  esac)"
done

log "DNS check"
SERVER_IP="$(public_ipv4)"
note "server IPv4: ${SERVER_IP:-unknown}"
note "required A records (both must point here):"
note "  $RELAY_HOST"
note "  *.$INSTANCE_DOMAIN"
if command -v getent >/dev/null 2>&1; then
  RESOLVED="$(getent ahostsv4 "$RELAY_HOST" 2>/dev/null | awk 'NR==1{print $1}' || true)"
  if [ -z "$RESOLVED" ]; then
    warn "$RELAY_HOST does not resolve yet; Caddy certificate issuance will fail"
  elif [ -n "$SERVER_IP" ] && [ "$RESOLVED" != "$SERVER_IP" ]; then
    warn "$RELAY_HOST resolves to $RESOLVED, not $SERVER_IP"
  else
    note "$RELAY_HOST -> $RESOLVED"
  fi
fi

if [ "$START" -eq 0 ]; then
  note "not starting containers (--no-start)"
else
  log "starting Caddy + relay"
  compose up -d --build
  if wait_health; then
    note "relay health: ok"
  else
    warn "relay did not become healthy within 120s; run: $0 --logs"
  fi
fi

log "done"
cat <<EOF

Relay host:        https://$RELAY_HOST
Instance host:     https://$INSTANCE_HOST
Management port:   http://127.0.0.1:18080/healthz

DSH plugin row (replace the placeholder values in your profile patch):

  mode: relay
  domain: $INSTANCE_HOST
  relayUrl: wss://$RELAY_HOST/tunnel
  instanceId: $INSTANCE_ID
  localPort: 3080

Instance token (store it with mode 0600; do not commit it):

  mkdir -p ~/.dsh/dsh-remote-tunnel
  chmod 700 ~/.dsh/dsh-remote-tunnel
  printf '%s\n' '$TOKEN' > ~/.dsh/dsh-remote-tunnel/instance-token
  chmod 600 ~/.dsh/dsh-remote-tunnel/instance-token

DNS records:

  A  $RELAY_HOST   -> ${SERVER_IP:-<server IPv4>}
  A  *.$INSTANCE_DOMAIN -> ${SERVER_IP:-<server IPv4>}

Next steps:

  1. Add both DNS records and wait for propagation.
  2. Edit the DSH profile's cordis.patch.yml with the row above.
  3. Write the token file, then restart dsh web.
  4. Open Plugins → dsh-remote-tunnel and generate the pairing QR code.

Useful commands:

  sudo bash install.sh --status
  sudo bash install.sh --logs
  sudo bash install.sh --upgrade
  sudo bash install.sh --uninstall [--purge]
EOF
