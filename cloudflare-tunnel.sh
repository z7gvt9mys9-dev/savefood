#!/usr/bin/env bash
# Starts the local Docker stack behind a Cloudflare Quick Tunnel.
# The tunnel URL is ephemeral: keep this process running while the site is in use.
set -Eeuo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_dir"

env_file_value() {
    local key="$1"
    [[ -f .env ]] || return 0
    awk -v key="$key" '
        index($0, key "=") == 1 {
            value = substr($0, length(key) + 2)
        }
        END { print value }
    ' .env
}

# An explicitly exported value takes precedence over .env, like Docker Compose.
app_port="${APP_PORT:-$(env_file_value APP_PORT)}"
app_port="${app_port:-80}"
bot_token="${TELEGRAM_BOT_TOKEN:-$(env_file_value TELEGRAM_BOT_TOKEN)}"
webhook_secret="${TELEGRAM_WEBHOOK_SECRET:-$(env_file_value TELEGRAM_WEBHOOK_SECRET)}"

if ! command -v cloudflared >/dev/null 2>&1; then
    echo "cloudflared is required. Install it, then run this script again." >&2
    exit 1
fi

runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/savefood-cloudflare.XXXXXX")"
tunnel_log="$runtime_dir/cloudflared.log"
tunnel_pid=""

cleanup() {
    if [[ -n "$tunnel_pid" ]] && kill -0 "$tunnel_pid" 2>/dev/null; then
        kill "$tunnel_pid" 2>/dev/null || true
        wait "$tunnel_pid" 2>/dev/null || true
    fi
    rm -rf "$runtime_dir"
}
trap cleanup EXIT INT TERM

echo "Starting SaveFood containers..."
docker compose up -d --build

echo "Starting Cloudflare Quick Tunnel for http://127.0.0.1:${app_port}..."
cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:${app_port}" >"$tunnel_log" 2>&1 &
tunnel_pid="$!"

public_url=""
for _ in {1..60}; do
    public_url="$(sed -nE 's/.*(https:\/\/[a-z0-9-]+\.trycloudflare\.com).*/\1/p' "$tunnel_log" | tail -n 1)"
    [[ -n "$public_url" ]] && break
    if ! kill -0 "$tunnel_pid" 2>/dev/null; then
        echo "Cloudflare Tunnel stopped before it provided a URL:" >&2
        tail -n 30 "$tunnel_log" >&2 || true
        exit 1
    fi
    sleep 1
done

if [[ -z "$public_url" ]]; then
    echo "Timed out waiting for the Cloudflare Quick Tunnel URL:" >&2
    tail -n 30 "$tunnel_log" >&2 || true
    exit 1
fi

echo "Public URL: $public_url"
echo "Restarting backend with the Cloudflare URL for site and OAuth links..."
SITE_URL="$public_url" OAUTH_PUBLIC_URL="$public_url" \
    docker compose up -d --force-recreate backend

if [[ -n "$bot_token" && -n "$webhook_secret" ]]; then
    tunnel_host="${public_url#https://}"
    # Telegram's resolver may not immediately see a new trycloudflare.com
    # record. Resolve through Cloudflare DoH and give Telegram the current IP;
    # it still uses the hostname for HTTPS/SNI and certificate verification.
    cloudflare_tunnel_ip() {
        curl --fail --silent --max-time 10 \
            --resolve cloudflare-dns.com:443:1.1.1.1 \
            -H 'accept: application/dns-json' \
            "https://cloudflare-dns.com/dns-query?name=${tunnel_host}&type=A" \
            | sed -nE 's/.*"data":"([0-9]{1,3}(\.[0-9]{1,3}){3})".*/\1/p' | head -n 1
    }

    # A Quick Tunnel hostname can take a few moments to propagate. Telegram's
    # setWebhook is the authoritative reachability check, so retry it rather
    # than relying on this machine's DNS resolver.
    echo "Registering the Telegram webhook (waiting for Cloudflare DNS if needed)..."
    webhook_registered=false
    for _ in {1..45}; do
        tunnel_ip="$(cloudflare_tunnel_ip || true)"
        if [[ -n "$tunnel_ip" ]] && curl --fail --silent --max-time 20 --get \
            "https://api.telegram.org/bot${bot_token}/setWebhook" \
            --data-urlencode "url=${public_url}/telegram/webhook" \
            --data-urlencode "secret_token=${webhook_secret}" \
            --data-urlencode "ip_address=${tunnel_ip}" >/dev/null; then
            webhook_registered=true
            break
        fi
        sleep 2
    done
    if [[ "$webhook_registered" == true ]]; then
        echo "Telegram webhook registered."
    else
        echo "Telegram could not reach the Quick Tunnel within 90 seconds; the site tunnel remains available." >&2
    fi
else
    echo "Telegram webhook skipped: TELEGRAM_BOT_TOKEN or TELEGRAM_WEBHOOK_SECRET is empty."
fi

echo "SaveFood is available at $public_url"
echo "Keep this terminal open: a Quick Tunnel is removed when this script stops."
wait "$tunnel_pid"
