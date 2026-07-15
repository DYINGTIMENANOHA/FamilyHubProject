#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/familyhub/familyhub.env}"
BACKEND_SERVICE="${BACKEND_SERVICE:-familyhub.service}"
LIVEKIT_SERVICE="${LIVEKIT_SERVICE:-familyhub-livekit.service}"
NGINX_SERVICE="${NGINX_SERVICE:-nginx.service}"
LIVEKIT_CONFIG="${LIVEKIT_CONFIG:-/opt/familyhub/livekit/livekit.yaml}"
BACKEND_DIR="${BACKEND_DIR:-/opt/familyhub/backend}"
BACKEND_USER="${BACKEND_USER:-www-data}"

load_env() {
  [ -f "$ENV_FILE" ] || return 0
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
}

usage() {
  cat <<EOF
Usage: bash manage-familyhub.sh <command>

Commands:
  status          Show backend, LiveKit, and nginx service status
  start           Start backend and LiveKit
  stop            Stop backend and LiveKit
  restart         Restart backend and LiveKit
  enable          Enable backend and LiveKit autostart
  disable         Disable backend and LiveKit autostart
  reload-nginx    Test and reload nginx
  health          Check FamilyHub public /health endpoint
  ports           Show listeners on FamilyHub ports
  logs            Follow backend logs
  logs-backend    Follow backend logs
  logs-livekit    Follow LiveKit logs
  logs-nginx      Follow nginx logs
  config          Print effective FamilyHub env config
  livekit-config  Print LiveKit server config
  fix-livekit-ip  Rewrite LiveKit config with explicit node_ip and restart it
  nginx-test      Run nginx -t
  account <args>  Manage accounts (forwards to familyhubctl.py account <args>)
  device <args>   Manage devices (forwards to familyhubctl.py device <args>)

Account examples:
  bash manage-familyhub.sh account add alice --password 'Secret123!'
  bash manage-familyhub.sh account list
  bash manage-familyhub.sh account disable alice
  bash manage-familyhub.sh account enable alice
  bash manage-familyhub.sh account reset-password alice --password 'NewSecret123!'
  bash manage-familyhub.sh account set-type alice test
  bash manage-familyhub.sh account set-livestream-env alice test
  bash manage-familyhub.sh account delete alice
  bash manage-familyhub.sh device list alice
  bash manage-familyhub.sh device kick alice <device_id>

Run 'bash manage-familyhub.sh account --help' or 'account <command> --help'
for the full list of account/device commands and options.
EOF
}

service_status() {
  systemctl status "$BACKEND_SERVICE" --no-pager || true
  echo
  systemctl status "$LIVEKIT_SERVICE" --no-pager || true
  echo
  systemctl status "$NGINX_SERVICE" --no-pager || true
}

service_start() {
  sudo systemctl start "$LIVEKIT_SERVICE"
  sudo systemctl start "$BACKEND_SERVICE"
}

service_stop() {
  sudo systemctl stop "$BACKEND_SERVICE" || true
  sudo systemctl stop "$LIVEKIT_SERVICE" || true
}

service_restart() {
  sudo systemctl restart "$LIVEKIT_SERVICE"
  sudo systemctl restart "$BACKEND_SERVICE"
}

service_enable() {
  sudo systemctl enable "$LIVEKIT_SERVICE"
  sudo systemctl enable "$BACKEND_SERVICE"
}

service_disable() {
  sudo systemctl disable "$BACKEND_SERVICE" || true
  sudo systemctl disable "$LIVEKIT_SERVICE" || true
}

reload_nginx() {
  sudo nginx -t
  sudo systemctl reload nginx
}

health_check() {
  load_env
  local base="${FAMILYHUB_PUBLIC_BASE_URL:-}"
  if [ -z "$base" ]; then
    echo "FAMILYHUB_PUBLIC_BASE_URL is not set in $ENV_FILE"
    exit 1
  fi
  base="${base%/}"
  curl -fsS "$base/health"
  echo
}

show_ports() {
  sudo ss -lntup | grep -E ':(8891|7880|7881|7882)\b' || true
}

show_config() {
  if [ ! -f "$ENV_FILE" ]; then
    echo "Missing env file: $ENV_FILE"
    exit 1
  fi
  sudo sed -E \
    -e 's/(FAMILYHUB_LIVEKIT_API_SECRET=).*/\1***hidden***/' \
    -e 's/(FAMILYHUB_.*TOKEN=).*/\1***hidden***/' \
    "$ENV_FILE"
}

show_livekit_config() {
  if [ ! -f "$LIVEKIT_CONFIG" ]; then
    echo "Missing LiveKit config: $LIVEKIT_CONFIG"
    exit 1
  fi
  sudo sed -E 's/(familyhub: ).*/\1***hidden***/' "$LIVEKIT_CONFIG"
}

fix_livekit_ip() {
  load_env
  local ip="${1:-${FAMILYHUB_LIVEKIT_NODE_IP:-}}"
  if [ -z "$ip" ]; then
    ip="$(curl -fsS --max-time 5 https://api.ipify.org || true)"
  fi
  if [ -z "$ip" ]; then
    echo "Could not determine public IP. Pass it explicitly:"
    echo "  bash manage-familyhub.sh fix-livekit-ip 103.30.77.178"
    exit 1
  fi

  local key="${FAMILYHUB_LIVEKIT_API_KEY:-familyhub}"
  local secret="${FAMILYHUB_LIVEKIT_API_SECRET:-}"
  local http_port="${FAMILYHUB_LIVEKIT_HTTP_PORT:-7880}"
  local tcp_port="${FAMILYHUB_LIVEKIT_TCP_PORT:-7881}"
  local udp_port="${FAMILYHUB_LIVEKIT_UDP_PORT:-7882}"
  if [ -z "$secret" ] && [ -f "$LIVEKIT_CONFIG" ]; then
    secret="$(sudo awk -F': ' -v key="$key" '$1 ~ "^[[:space:]]*" key "$" {print $2}' "$LIVEKIT_CONFIG" | head -n 1)"
  fi
  if [ -z "$secret" ]; then
    echo "Missing LiveKit API secret in $ENV_FILE and $LIVEKIT_CONFIG"
    exit 1
  fi

  sudo mkdir -p "$(dirname "$LIVEKIT_CONFIG")"
  if [ -f "$LIVEKIT_CONFIG" ]; then
    sudo cp "$LIVEKIT_CONFIG" "$LIVEKIT_CONFIG.$(date +%Y%m%d_%H%M%S).bak"
  fi
  sudo tee "$LIVEKIT_CONFIG" >/dev/null <<EOF2
port: $http_port
bind_addresses:
  - 0.0.0.0
rtc:
  node_ip: $ip
  tcp_port: $tcp_port
  udp_port: $udp_port
  use_external_ip: false
keys:
  $key: $secret
EOF2
  sudo chmod 600 "$LIVEKIT_CONFIG"
  sudo systemctl restart "$LIVEKIT_SERVICE"
  sleep 3
  systemctl status "$LIVEKIT_SERVICE" --no-pager -l || true
}

run_ctl() {
  if [ ! -f "$ENV_FILE" ]; then
    echo "Missing env file: $ENV_FILE"
    exit 1
  fi
  if [ ! -x "$BACKEND_DIR/.venv/bin/python" ]; then
    echo "Missing backend virtualenv: $BACKEND_DIR/.venv/bin/python"
    exit 1
  fi
  # shellcheck disable=SC2046
  sudo -u "$BACKEND_USER" env $(sudo cat "$ENV_FILE" | xargs) \
    "$BACKEND_DIR/.venv/bin/python" "$BACKEND_DIR/familyhubctl.py" "$@"
}

case "${1:-}" in
  status) service_status ;;
  start) service_start ;;
  stop) service_stop ;;
  restart) service_restart ;;
  enable) service_enable ;;
  disable) service_disable ;;
  reload-nginx) reload_nginx ;;
  health) health_check ;;
  ports) show_ports ;;
  logs|logs-backend) journalctl -u "$BACKEND_SERVICE" -f ;;
  logs-livekit) journalctl -u "$LIVEKIT_SERVICE" -f ;;
  logs-nginx) journalctl -u "$NGINX_SERVICE" -f ;;
  config) show_config ;;
  livekit-config) show_livekit_config ;;
  fix-livekit-ip) fix_livekit_ip "${2:-}" ;;
  nginx-test) sudo nginx -t ;;
  account) shift; run_ctl account "$@" ;;
  device) shift; run_ctl device "$@" ;;
  ""|-h|--help|help) usage ;;
  *) echo "Unknown command: $1"; echo; usage; exit 1 ;;
esac
