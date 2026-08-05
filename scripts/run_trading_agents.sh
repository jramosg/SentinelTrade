#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bot_dir="$(cd "$script_dir/.." && pwd)"
python="$bot_dir/.venv-tradingagents/bin/python"

if [[ ! -x "$python" ]]; then
  echo "TradingAgents environment is missing." >&2
  echo "Run scripts/setup_trading_agents.sh first." >&2
  exit 1
fi

exec "$python" "$script_dir/trading_agents.py" "$@"
