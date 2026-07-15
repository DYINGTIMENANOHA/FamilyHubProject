#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_SOURCE_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONFIG_FILE="${CONFIG_FILE:-$SCRIPT_DIR/familyhub.deploy.conf}"

load_config() {
  local file="$1"
  [ -f "$file" ] || return 0
  local raw line key value
  while IFS= read -r raw || [ -n "$raw" ]; do
    line="${raw#"${raw%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [ -z "$line" ] && continue
    case "$line" in \#*) continue ;; esac
    case "$line" in
      *=*)
        key="${line%%=*}"
        value="${line#*=}"
        key="${key#"${key%%[![:space:]]*}"}"
        key="${key%"${key##*[![:space:]]}"}"
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        value="${value%\"}"; value="${value#\"}"
        value="${value%\'}"; value="${value#\'}"
        if [ -z "${!key+x}" ]; then
          export "$key=$value"
        fi
        ;;
    esac
  done < "$file"
}

ask() {
  local name="$1"
  local label="$2"
  local default_value="$3"
  local value
  if [ -n "${!name:-}" ]; then
    return 0
  fi
  read -r -p "$label [$default_value]: " value
  export "$name=${value:-$default_value}"
}

ask_yes_no() {
  local name="$1"
  local label="$2"
  local default_value="$3"
  local value suffix
  if [ -n "${!name:-}" ]; then
    return 0
  fi
  case "$default_value" in
    yes|YES|y|Y|true|1) suffix="Y/n" ;;
    *) suffix="y/N" ;;
  esac
  read -r -p "$label [$suffix]: " value
  value="${value:-$default_value}"
  case "$value" in
    yes|YES|y|Y|true|1) export "$name=yes" ;;
    *) export "$name=no" ;;
  esac
}

ask_confirm() {
  local name="$1"
  local label="$2"
  local fallback_value="$3"
  local default_value value
  default_value="${!name:-$fallback_value}"
  read -r -p "$label [$default_value]: " value
  export "$name=${value:-$default_value}"
}

random_secret() {
  openssl rand -hex 32
}

normalize_path() {
  local value="$1"
  if [[ "$value" != /* ]]; then
    value="/$value"
  fi
  value="${value%/}"
  echo "$value"
}

require_ubuntu() {
  if [ ! -f /etc/os-release ]; then
    echo "Cannot detect OS. This script is intended for Ubuntu."
    exit 1
  fi
  . /etc/os-release
  if [ "${ID:-}" != "ubuntu" ]; then
    echo "Warning: detected ${PRETTY_NAME:-unknown}, but this script is designed for Ubuntu."
    read -r -p "Continue anyway? [y/N]: " answer
    case "$answer" in y|Y|yes|YES) ;; *) exit 1 ;; esac
  fi
}

write_env_file() {
  local env_file="$1"
  sudo mkdir -p "$(dirname "$env_file")"
  sudo tee "$env_file" >/dev/null <<EOF
FAMILYHUB_PUBLIC_BASE_URL=$PUBLIC_BASE_URL
FAMILYHUB_DATABASE_URL=$DATABASE_URL
FAMILYHUB_STORAGE_DIR=$STORAGE_DIR
FAMILYHUB_UPLOADS_DIR=$UPLOADS_DIR
FAMILYHUB_DEFAULT_MAX_DEVICES=2
FAMILYHUB_LIVEKIT_URL=$LIVEKIT_PUBLIC_URL
FAMILYHUB_LIVEKIT_API_KEY=$LIVEKIT_API_KEY
FAMILYHUB_LIVEKIT_API_SECRET=$LIVEKIT_API_SECRET
FAMILYHUB_LIVEKIT_NODE_IP=$LIVEKIT_NODE_IP
FAMILYHUB_LIVEKIT_HTTP_PORT=$LIVEKIT_HTTP_PORT
FAMILYHUB_LIVEKIT_TCP_PORT=$LIVEKIT_TCP_PORT
FAMILYHUB_LIVEKIT_UDP_PORT=$LIVEKIT_UDP_PORT
FAMILYHUB_LIVEKIT_TOKEN_TTL_SECONDS=3600
FAMILYHUB_LIVE_ROOM_STALE_SECONDS=600
FAMILYHUB_LIVE_ROOM_CLEANUP_INTERVAL_SECONDS=30
FAMILYHUB_LEGACY_LIVESTREAM_BASE_URL=$LIVESTREAM_BASE_URL
FAMILYHUB_LIVESTREAM_BASE_URL=$LIVESTREAM_BASE_URL
FAMILYHUB_LEGACY_CINEMA_BASE_URL=$CINEMA_BASE_URL
FAMILYHUB_CINEMA_BASE_URL=$CINEMA_BASE_URL
FAMILYHUB_AUTH_TOKENS_DB=$AUTH_TOKENS_DB
FAMILYHUB_AUTH_KEYS_DIR=$AUTH_KEYS_DIR
FAMILYHUB_CINEMA_WATCH_TOKEN=$CINEMA_WATCH_TOKEN
FAMILYHUB_CINEMA_ADMIN_TOKEN=$CINEMA_ADMIN_TOKEN
FAMILYHUB_TEST_CINEMA_BASE_URL=$TEST_CINEMA_BASE_URL
FAMILYHUB_TEST_CINEMA_WATCH_TOKEN=$TEST_CINEMA_WATCH_TOKEN
FAMILYHUB_TEST_CINEMA_ADMIN_TOKEN=$TEST_CINEMA_ADMIN_TOKEN
FAMILYHUB_LIVESTREAM_WATCH_TOKEN=$LIVESTREAM_WATCH_TOKEN
FAMILYHUB_LIVESTREAM_TEST_WATCH_TOKEN=$LIVESTREAM_TEST_WATCH_TOKEN
FAMILYHUB_INTEGRATION_ADMINS=$INTEGRATION_ADMINS
FAMILYHUB_INTEGRATION_LAUNCH_TTL_SECONDS=$INTEGRATION_LAUNCH_TTL_SECONDS
EOF
  sudo chmod 600 "$env_file"
}

install_backend() {
  echo
  echo "==> Installing FamilyHub backend to $BACKEND_DIR"
  sudo apt-get update
  sudo apt-get install -y python3 python3-venv python3-pip sqlite3 rsync

  sudo mkdir -p "$BACKEND_DIR" "$STORAGE_DIR" "$UPLOADS_DIR" "$INSTALL_ROOT/logs"
  sudo rsync -a --delete \
    --exclude "__pycache__" \
    --exclude ".venv" \
    --exclude "familyhub.db" \
    --exclude "storage" \
    "$BACKEND_SOURCE_DIR/" "$BACKEND_DIR/"

  sudo install -m 0755 "$SCRIPT_DIR/manage-familyhub.sh" "$INSTALL_ROOT/manage-familyhub.sh"

  sudo chown -R "$BACKEND_USER:$BACKEND_GROUP" "$INSTALL_ROOT"
  sudo chown root:root "$INSTALL_ROOT/manage-familyhub.sh"
  sudo chmod 0755 "$INSTALL_ROOT/manage-familyhub.sh"

  cd "$BACKEND_DIR"
  sudo -u "$BACKEND_USER" python3 -m venv .venv
  sudo -u "$BACKEND_USER" .venv/bin/pip install --upgrade pip
  sudo -u "$BACKEND_USER" .venv/bin/pip install -r requirements.txt

  local env_file="$INSTALL_ROOT/familyhub.env"
  write_env_file "$env_file"

  sudo -u "$BACKEND_USER" env $(sudo cat "$env_file" | xargs) .venv/bin/python -c "from database import init_db; init_db()"

  sudo tee /etc/systemd/system/familyhub.service >/dev/null <<EOF
[Unit]
Description=FamilyHub FastAPI Backend
After=network.target

[Service]
Type=simple
User=$BACKEND_USER
Group=$BACKEND_GROUP
WorkingDirectory=$BACKEND_DIR
EnvironmentFile=$env_file
ExecStart=$BACKEND_DIR/.venv/bin/python -m uvicorn main:app --host $BACKEND_HOST --port $BACKEND_PORT
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

  sudo systemctl daemon-reload
  sudo systemctl enable familyhub.service
  sudo systemctl restart familyhub.service
}

install_livekit() {
  [ "$INSTALL_LIVEKIT" = "yes" ] || return 0
  echo
  echo "==> Installing LiveKit with Docker"
  sudo apt-get install -y docker.io
  sudo systemctl enable --now docker

  local livekit_dir="$INSTALL_ROOT/livekit"
  sudo mkdir -p "$livekit_dir"
  sudo tee "$livekit_dir/livekit.yaml" >/dev/null <<EOF
port: $LIVEKIT_HTTP_PORT
bind_addresses:
  - 0.0.0.0
rtc:
  node_ip: $LIVEKIT_NODE_IP
  tcp_port: $LIVEKIT_TCP_PORT
  udp_port: $LIVEKIT_UDP_PORT
  use_external_ip: false
keys:
  $LIVEKIT_API_KEY: $LIVEKIT_API_SECRET
EOF
  sudo chmod 600 "$livekit_dir/livekit.yaml"

  sudo tee /etc/systemd/system/familyhub-livekit.service >/dev/null <<EOF
[Unit]
Description=FamilyHub LiveKit Server
After=docker.service network-online.target
Requires=docker.service

[Service]
Type=simple
Restart=always
RestartSec=5
ExecStartPre=-/usr/bin/docker rm -f familyhub-livekit
ExecStart=/usr/bin/docker run --rm --name familyhub-livekit --network host -v $livekit_dir/livekit.yaml:/livekit.yaml:ro livekit/livekit-server:latest --config /livekit.yaml
ExecStop=/usr/bin/docker stop familyhub-livekit

[Install]
WantedBy=multi-user.target
EOF

  sudo systemctl daemon-reload
  sudo systemctl enable familyhub-livekit.service
  sudo systemctl restart familyhub-livekit.service
}

configure_nginx() {
  [ "$CONFIGURE_NGINX" = "yes" ] || return 0
  echo
  echo "==> Configuring nginx"
  sudo apt-get install -y nginx
  sudo mkdir -p "$(dirname "$NGINX_SNIPPET")"

  local public_path
  public_path="$(normalize_path "$PUBLIC_BASE_PATH")"

  local tmp_snippet
  tmp_snippet="$(mktemp)"
  sed \
    -e "s|__PUBLIC_BASE_PATH__|$public_path|g" \
    -e "s|__BACKEND_HOST__|$BACKEND_HOST|g" \
    -e "s|__BACKEND_PORT__|$BACKEND_PORT|g" \
    -e "s|__LIVEKIT_HTTP_PORT__|$LIVEKIT_HTTP_PORT|g" \
    "$SCRIPT_DIR/familyhub-nginx-locations.conf.template" > "$tmp_snippet"
  sudo mv "$tmp_snippet" "$NGINX_SNIPPET"

  if [ ! -f "$NGINX_SITE_CONF" ]; then
    echo "Nginx site config not found: $NGINX_SITE_CONF"
    echo "Creating a new HTTP-only site for $DOMAIN. If HTTPS already exists, rerun with NGINX_SITE_CONF pointing to it."
    sudo tee "$NGINX_SITE_CONF" >/dev/null <<EOF
server {
    listen 80;
    server_name $DOMAIN;
    include $NGINX_SNIPPET;
}
EOF
    sudo ln -sfn "$NGINX_SITE_CONF" "/etc/nginx/sites-enabled/$(basename "$NGINX_SITE_CONF")"
  else
    local backup
    backup="$NGINX_SITE_CONF.$(date +%Y%m%d_%H%M%S).bak"
    sudo cp "$NGINX_SITE_CONF" "$backup"
    sudo python3 - "$NGINX_SITE_CONF" "$NGINX_SNIPPET" <<'PY'
import sys
from pathlib import Path

conf = Path(sys.argv[1])
snippet = sys.argv[2]
text = conf.read_text()
begin = "# FAMILYHUB MANAGED BEGIN"
end = "# FAMILYHUB MANAGED END"
block = f"\n    {begin}\n    include {snippet};\n    {end}\n"

if begin in text and end in text:
    start = text.index(begin)
    line_start = text.rfind("\n", 0, start)
    finish = text.index(end, start) + len(end)
    line_end = text.find("\n", finish)
    if line_end == -1:
        line_end = len(text)
    text = text[:line_start] + block.rstrip("\n") + text[line_end:]
else:
    pos = text.rfind("}")
    if pos == -1:
        raise SystemExit("No closing brace found in nginx config")
    text = text[:pos] + block + text[pos:]

conf.write_text(text)
PY
  fi

  if sudo nginx -t; then
    sudo systemctl enable --now nginx
    sudo systemctl reload nginx
  else
    echo "nginx -t failed."
    if [ -n "${backup:-}" ] && [ -f "$backup" ]; then
      echo "Restoring backup: $backup"
      sudo cp "$backup" "$NGINX_SITE_CONF"
    fi
    exit 1
  fi
}

preview_nginx_plan() {
  [ "${CONFIGURE_NGINX:-yes}" = "yes" ] || return 0
  [ "${PREVIEW_NGINX_CONFIG:-yes}" = "yes" ] || return 0

  echo
  echo "==> Nginx preflight preview"
  echo "Target site config: $NGINX_SITE_CONF"
  echo "FamilyHub snippet:  $NGINX_SNIPPET"
  echo
  echo "Ports that would be used by FamilyHub:"
  echo "  Backend: $BACKEND_HOST:$BACKEND_PORT"
  echo "  LiveKit HTTP/WebSocket: $LIVEKIT_HTTP_PORT"
  echo "  LiveKit ICE TCP: $LIVEKIT_TCP_PORT"
  echo "  LiveKit ICE UDP: $LIVEKIT_UDP_PORT"
  echo
  echo "Current listeners on those ports, if any:"
  (sudo ss -lntup 2>/dev/null || ss -lntup 2>/dev/null || true) | grep -E ":($BACKEND_PORT|$LIVEKIT_HTTP_PORT|$LIVEKIT_TCP_PORT|$LIVEKIT_UDP_PORT)\b" || true

  echo
  echo "Generated FamilyHub nginx snippet preview:"
  local public_path
  public_path="$(normalize_path "$PUBLIC_BASE_PATH")"
  sed \
    -e "s|__PUBLIC_BASE_PATH__|$public_path|g" \
    -e "s|__BACKEND_HOST__|$BACKEND_HOST|g" \
    -e "s|__BACKEND_PORT__|$BACKEND_PORT|g" \
    -e "s|__LIVEKIT_HTTP_PORT__|$LIVEKIT_HTTP_PORT|g" \
    "$SCRIPT_DIR/familyhub-nginx-locations.conf.template"

  echo
  if [ -f "$NGINX_SITE_CONF" ]; then
    echo "Current nginx site config preview: $NGINX_SITE_CONF"
    echo "----- BEGIN CURRENT NGINX CONFIG -----"
    sudo sed -n '1,260p' "$NGINX_SITE_CONF"
    echo "----- END CURRENT NGINX CONFIG -----"
    echo "If the config is longer than 260 lines, inspect it manually before continuing."
  else
    echo "Nginx site config does not exist yet. The script would create it:"
    echo "$NGINX_SITE_CONF"
  fi
}

configure_ufw() {
  [ "$CONFIGURE_UFW" = "yes" ] || return 0
  if ! command -v ufw >/dev/null 2>&1; then
    sudo apt-get install -y ufw
  fi
  sudo ufw allow "$LIVEKIT_TCP_PORT/tcp"
  sudo ufw allow "$LIVEKIT_UDP_PORT/udp"
}

print_summary() {
  echo
  echo "==> FamilyHub deployment complete"
  echo "Backend service: familyhub.service"
  echo "LiveKit service: familyhub-livekit.service"
  echo "Backend public URL: $PUBLIC_BASE_URL"
  echo "LiveKit public URL: $LIVEKIT_PUBLIC_URL"
  echo
  echo "Check commands:"
  echo "  systemctl status familyhub --no-pager"
  echo "  systemctl status familyhub-livekit --no-pager"
  echo "  curl -fsS $PUBLIC_BASE_URL/health"
  echo "  bash $INSTALL_ROOT/manage-familyhub.sh status"
  echo
  echo "Management:"
  echo "  bash $INSTALL_ROOT/manage-familyhub.sh start"
  echo "  bash $INSTALL_ROOT/manage-familyhub.sh stop"
  echo "  bash $INSTALL_ROOT/manage-familyhub.sh restart"
  echo "  bash $INSTALL_ROOT/manage-familyhub.sh logs"
  echo "  bash $INSTALL_ROOT/manage-familyhub.sh health"
  echo
  echo "Android release SERVER_BASE_URL should be:"
  echo "  $PUBLIC_BASE_URL/"
}

main() {
  require_ubuntu
  load_config "$CONFIG_FILE"

  ask DOMAIN "Public domain" "streamforsoul.com"
  ask PUBLIC_BASE_PATH "FamilyHub public path" "/familyhub"
  PUBLIC_BASE_PATH="$(normalize_path "$PUBLIC_BASE_PATH")"
  ask PUBLIC_BASE_URL "FamilyHub public base URL" "https://$DOMAIN:8443$PUBLIC_BASE_PATH"
  ask LIVEKIT_PUBLIC_URL "LiveKit public WebSocket URL" "wss://$DOMAIN:8443/livekit/"
  ask CINEMA_BASE_URL "Existing Cinema public base URL" "https://$DOMAIN:8443/cinema/"
  ask LIVESTREAM_BASE_URL "Existing Livestream public base URL" "https://$DOMAIN:8443/"
  ask AUTH_TOKENS_DB "Existing auth token database path" "/opt/auth/data/tokens.db"
  ask AUTH_KEYS_DIR "Existing auth key directory" "/opt/auth/keys"
  ask INTEGRATION_LAUNCH_TTL_SECONDS "FamilyHub integration launch link TTL seconds" "120"
  CINEMA_WATCH_TOKEN="${CINEMA_WATCH_TOKEN:-}"
  CINEMA_ADMIN_TOKEN="${CINEMA_ADMIN_TOKEN:-}"
  TEST_CINEMA_BASE_URL="${TEST_CINEMA_BASE_URL:-https://cn.streamforsoul.com/cinema/}"
  TEST_CINEMA_WATCH_TOKEN="${TEST_CINEMA_WATCH_TOKEN:-b9a75259ab75f1b80489b291a830081f}"
  TEST_CINEMA_ADMIN_TOKEN="${TEST_CINEMA_ADMIN_TOKEN:-289e17f38e95263d58e51ed499181698}"
  LIVESTREAM_WATCH_TOKEN="${LIVESTREAM_WATCH_TOKEN:-}"
  LIVESTREAM_TEST_WATCH_TOKEN="${LIVESTREAM_TEST_WATCH_TOKEN:-}"
  ask INTEGRATION_ADMINS "FamilyHub integration admin nicknames/user ids" "${INTEGRATION_ADMINS:-alice}"

  ask INSTALL_ROOT "Install root" "/opt/familyhub"
  ask BACKEND_DIR "Backend install directory" "$INSTALL_ROOT/backend"
  ask STORAGE_DIR "Storage directory" "$INSTALL_ROOT/storage"
  ask UPLOADS_DIR "Uploads directory" "$STORAGE_DIR/uploads"
  ask DATABASE_URL "Database URL" "sqlite:///$BACKEND_DIR/familyhub.db"
  ask BACKEND_HOST "Backend bind host" "127.0.0.1"
  ask BACKEND_PORT "Backend internal port" "8891"
  ask BACKEND_USER "Backend user" "www-data"
  ask BACKEND_GROUP "Backend group" "www-data"

  ask_yes_no INSTALL_LIVEKIT "Install LiveKit service" "yes"
  ask LIVEKIT_HTTP_PORT "LiveKit HTTP/WebSocket internal port" "7880"
  ask LIVEKIT_TCP_PORT "LiveKit ICE TCP port" "7881"
  ask LIVEKIT_UDP_PORT "LiveKit ICE UDP port" "7882"
  detected_ip="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || curl -fsS --max-time 5 https://ifconfig.me 2>/dev/null || true)"
  detected_ip="${detected_ip:-103.30.77.178}"
  ask_confirm LIVEKIT_NODE_IP "LiveKit public node IP" "$detected_ip"
  ask LIVEKIT_API_KEY "LiveKit API key" "familyhub"
  if [ -z "${LIVEKIT_API_SECRET:-}" ]; then
    generated_secret="$(random_secret)"
    ask LIVEKIT_API_SECRET "LiveKit API secret" "$generated_secret"
  fi

  ask_yes_no CONFIGURE_NGINX "Configure nginx reverse proxy" "yes"
  ask_yes_no PREVIEW_NGINX_CONFIG "Preview current/generated nginx config before changes" "yes"
  ask NGINX_SITE_CONF "Nginx site config to update" "/etc/nginx/sites-available/livestream"
  ask NGINX_SNIPPET "Nginx FamilyHub snippet path" "/etc/nginx/snippets/familyhub-locations.conf"
  ask_yes_no CONFIGURE_UFW "Open LiveKit RTC ports with ufw" "no"

  echo
  echo "Deployment plan:"
  echo "  Domain: $DOMAIN"
  echo "  FamilyHub URL: $PUBLIC_BASE_URL"
  echo "  LiveKit URL: $LIVEKIT_PUBLIC_URL"
  echo "  Cinema URL: $CINEMA_BASE_URL"
  echo "  Livestream URL: $LIVESTREAM_BASE_URL"
  echo "  Backend: $BACKEND_HOST:$BACKEND_PORT -> $BACKEND_DIR"
  echo "  LiveKit ports: http=$LIVEKIT_HTTP_PORT tcp=$LIVEKIT_TCP_PORT udp=$LIVEKIT_UDP_PORT"
  echo "  LiveKit node IP: $LIVEKIT_NODE_IP"
  echo "  Nginx config: $NGINX_SITE_CONF"
  preview_nginx_plan
  read -r -p "Continue deployment? [y/N]: " confirm
  case "$confirm" in y|Y|yes|YES) ;; *) echo "Cancelled."; exit 0 ;; esac

  install_backend
  install_livekit
  configure_nginx
  configure_ufw
  print_summary
}

main "$@"
