#!/bin/bash
# Runs ON the VPS (from ~/richbot). Pulls the latest master and
# restarts only the services whose code actually changed.
#
# Safety: the real-money crypto process (portfolio-live) is NEVER
# auto-restarted — it only gets a Telegram nudge — so an automated
# deploy can never bounce live trading unattended. The stocks advisor
# (recommendations only, no orders) is safe to restart automatically.
# Dip-scanner changes need no restart: the next cron run uses them.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi

notify() {
  [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ] || return 0
  curl -s -X POST \
    "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
    -d "chat_id=${TELEGRAM_CHAT_ID}" \
    --data-urlencode "text=$1" > /dev/null || true
}

OLD=$(git rev-parse HEAD)
git fetch --quiet origin master
# Reset to the remote: only tracked files move. Secrets, state, logs
# and personal CSVs are gitignored, so they are never touched.
git reset --hard --quiet origin/master
NEW=$(git rev-parse HEAD)

if [ "$OLD" = "$NEW" ]; then
  echo "Already up to date at ${NEW:0:7}"
  exit 0
fi

CHANGED=$(git diff --name-only "$OLD" "$NEW")
echo "Deployed ${OLD:0:7} -> ${NEW:0:7}. Changed files:"
echo "$CHANGED"

restart_advisor=false
warn_portfolio=false
echo "$CHANGED" | grep -qE \
  'deps\.edn|src/richbot/(stocks|dca|strategy|stocks_data|indicators)\.clj' \
  && restart_advisor=true
echo "$CHANGED" | grep -qE \
  'deps\.edn|src/richbot/(live|binance|strategy|indicators)\.clj' \
  && warn_portfolio=true

if $restart_advisor; then
  echo "Restarting stocks-advisor..."
  pkill -f "richbot.core stocks[-]advisor" || true
  sleep 2
  nohup /usr/local/bin/clojure -M:stocks-advisor \
    >> stocks-advisor.log 2>&1 &
fi

msg="richbot deployed ${NEW:0:7}"
$restart_advisor && msg="$msg | stocks-advisor restarted"
$warn_portfolio && msg="$msg | WARNING: portfolio-live (real money) may need a manual restart"
notify "$msg"
echo "Done."
