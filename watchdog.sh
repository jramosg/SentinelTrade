#!/bin/bash
# Restarts richbot's long-running processes if they die and alerts
# by Telegram. Runs from cron every 10 minutes.
#
#   touch ~/richbot/.maintenance
#
# disables it while you work on the VPS (remove the file after).
# Note: if a process keeps crashing (e.g. exchange unreachable),
# this restarts it at most once per cron run — expect one Telegram
# alert per cycle until the cause is fixed.
set -u
cd "$(dirname "$0")"
[ -f .maintenance ] && exit 0
set -a; source .env; set +a

alert() {
  curl -s -X POST \
    "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
    -d chat_id="${TELEGRAM_CHAT_ID}" \
    --data-urlencode text="$1" > /dev/null
}

ensure() {
  local pattern="$1" alias="$2" log="$3" extra="${4:-}"
  if ! pgrep -f "$pattern" > /dev/null; then
    # shellcheck disable=SC2086
    nohup clojure "-M:$alias" $extra >> "$log" 2>&1 &
    alert "WATCHDOG richbot: $alias estaba caido, reiniciado $(date -u +%FT%TZ). Revisa $log si se repite."
    echo "$(date -u +%FT%TZ) restarted $alias"
  fi
}

# portfolio-live (crypto, real money) is intentionally NOT auto-ensured:
# stopped 2026-07-01 after repeated Binance 401s ("Invalid API-key, IP,
# or permissions" - account/API access issue, not a transient outage).
# Re-enable only after confirming Binance access works again:
#   ensure "richbot.core portfolio-live" portfolio-live portfolio-live.log yes
ensure "richbot.core stocks-advisor" stocks-advisor stocks-advisor.log
