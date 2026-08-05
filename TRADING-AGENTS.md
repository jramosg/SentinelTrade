# TradingAgents research layer

Richbot can run the sibling `../TradingAgents` Python project as an optional,
read-only research layer. It produces analyst, bull/bear, trader, risk, and
portfolio-manager reports for a ticker. It does **not** place an order, change
Richbot portfolio state, or send Telegram advice.

## Why it is isolated

TradingAgents is useful for unstructured information: fundamentals, news,
social sentiment, and an explicit bull/bear critique. Its final rating is
LLM-generated and non-deterministic. Richbot's deterministic signals,
walk-forward evidence, position budgets, and kill switches therefore remain
the only inputs to execution.

Treat a TradingAgents rating as a research hypothesis. Do not treat it as a
validated trading strategy until enough forward, timestamped decisions have
been collected to compare against a passive benchmark and the existing
Richbot signal after costs.

## Setup

The setup script creates an isolated Python 3.12 environment in
`.venv-tradingagents` and installs the local sibling checkout. It does not copy
or fork TradingAgents.

```sh
scripts/setup_trading_agents.sh
scripts/run_trading_agents.sh --check
```

The runner reads process environment variables, then missing values from this
repository's `.env`, then from `../TradingAgents/.env`. At minimum, configure
the API key for the selected LLM provider. Optional `TRADINGAGENTS_*` variables
from the upstream project are honored.

## Run research

```sh
# Full stock research for today
scripts/run_trading_agents.sh COST

# Historical price-data cutoff and Spanish output
scripts/run_trading_agents.sh NVDA --date 2026-07-31 \
  --output-language Spanish

# Lower-cost first experiment
scripts/run_trading_agents.sh COST \
  --analysts market,news,fundamentals \
  --debate-rounds 1 --risk-rounds 1

# Crypto automatically drops the fundamentals analyst
scripts/run_trading_agents.sh BTC-USD
```

Use `--provider`, `--quick-model`, and `--deep-model` to override upstream
defaults. Run `scripts/run_trading_agents.sh --help` for every option.

## Outputs and evaluation

Readable reports are written under `reports/trading-agents/<ticker>/`. Cache,
checkpoint, upstream state logs, memory, and an append-only `decisions.jsonl`
history live under `.richbot-trading-agents/`. Both locations are local runtime
artifacts and are ignored by Git.

The safe rollout is shadow mode:

1. Run it on a small, fixed watchlist on a fixed schedule.
2. Preserve every timestamped decision, including failures and `Hold` ratings.
3. Evaluate forward returns and alpha after 1, 5, 21, and 63 trading days.
4. Compare with buy-and-hold and Richbot's existing signal on the same dates.
5. Consider a later integration only if the incremental signal survives costs,
   multiple market regimes, and a predeclared acceptance threshold.

Historical reruns cannot validate news or social-sentiment performance unless
every source is strictly cut off at the analysis date. The upstream project
itself notes that those sources can still reflect current information.
