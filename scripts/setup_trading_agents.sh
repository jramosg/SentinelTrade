#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bot_dir="$(cd "$script_dir/.." && pwd)"
trading_agents_repo="${TRADINGAGENTS_REPO:-$bot_dir/../TradingAgents}"
venv="$bot_dir/.venv-tradingagents"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv is required: https://docs.astral.sh/uv/" >&2
  exit 1
fi

if [[ ! -f "$trading_agents_repo/pyproject.toml" ]]; then
  echo "TradingAgents checkout not found: $trading_agents_repo" >&2
  exit 1
fi

uv venv --python 3.12 "$venv"
uv pip install --python "$venv/bin/python" -e "$trading_agents_repo"

echo "TradingAgents environment ready."
echo "Check it with: scripts/run_trading_agents.sh --check"
