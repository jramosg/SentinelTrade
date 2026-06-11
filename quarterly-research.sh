#!/bin/bash
# Quarterly richbot research review: re-runs the stock and crypto
# walk-forward research and sends the ACCEPT summary by Telegram.
# Slots are only changed manually, with rules (see STOCKS.md):
# a slot stopped out, its strategy degraded below the rules on
# refreshed data, or a non-held candidate dominates it on both
# return and drawdown.
set -u
cd "$(dirname "$0")"
set -a; source .env; set +a

{
  echo "=== STOCKS (walk-forward 15y) ==="
  clojure -M:stocks-research 2>&1
  echo
  echo "=== CRYPTO (walk-forward 6y 4h) ==="
  clojure -M:research 2>&1
} > quarterly-research.log

# Compact lines (symbol strategy oos sharpe maxdd) so the full
# ACCEPT list fits Telegram's 4096-char message limit.
SUMMARY=$(grep "ACCEPT" quarterly-research.log | grep -v "ACCEPTED" \
          | sort \
          | awk -F, '{printf "%s %s oos %s sh %s dd %s\n", \
                      $1, $2, $4, $6, $7}' \
          | head -c 3500)

curl -s -X POST \
  "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
  -d chat_id="${TELEGRAM_CHAT_ID}" \
  --data-urlencode text="RESEARCH TRIMESTRAL richbot completado.
Candidatos que pasan las reglas:

${SUMMARY:-ninguno}

Log completo: ~/richbot/quarterly-research.log
Revisa con Claude si conviene cambiar algun slot." > /dev/null
echo "done $(date -u +%FT%TZ)"
