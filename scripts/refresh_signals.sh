#!/bin/bash
# Refresh TimesFM signals locally and push to VPS.
# Reads VPS_USER, VPS_HOST, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID from .env
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SCRIPT_DIR"

# Source .env safely: `set -a` exports everything assigned while it is
# on, so values with spaces, quotes or `=` survive intact (the old
# `export $(... | xargs)` mangled them).
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi

VPS_USER="${VPS_USER:-root}"
VPS_HOST="${VPS_HOST:?Set VPS_HOST in .env}"

echo "Running TimesFM signals..."
python3 scripts/timesfm_signals.py --out resources/timesfm_signals.json

echo "Syncing to VPS..."
rsync -av resources/timesfm_signals.json \
  "${VPS_USER}@${VPS_HOST}:~/richbot/resources/timesfm_signals.json"

if [ -n "$TELEGRAM_BOT_TOKEN" ] && [ -n "$TELEGRAM_CHAT_ID" ]; then
  MODE=$(python3 -c "
import json
with open('resources/timesfm_signals.json') as f:
    d = json.load(f)
sigs = {k: v for k, v in d.items() if not k.startswith('_')}
modes = set(v.get('mode','?') for v in sigs.values())
print(list(modes)[0] if len(modes)==1 else ','.join(sorted(modes)))
")
  COUNT=$(python3 -c "
import json
with open('resources/timesfm_signals.json') as f:
    d = json.load(f)
print(sum(1 for k in d if not k.startswith('_')))
")
  curl -s -X POST \
    "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
    -d "chat_id=${TELEGRAM_CHAT_ID}" \
    --data-urlencode "text=✅ TimesFM signals refreshed: ${COUNT} symbols [${MODE}] → VPS synced" \
    > /dev/null
fi

echo "Done."
