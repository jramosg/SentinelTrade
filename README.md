# richbot

A crypto trading bot in Clojure: Binance market data, three strategies,
a fee/slippage-aware backtester, rolling walk-forward validation, and a
live trading loop against the Binance Spot Testnet (real signed orders,
fake money).

## Usage

```sh
# Backtest the default strategy (donchian, 4h) on ~16 months of data
clojure -M:backtest

# Walk-forward validate all strategies (interval and symbol optional)
clojure -M:walkforward            # 1h ETHUSDC
clojure -M:walkforward 4h         # 4h ETHUSDC
clojure -M -m richbot.core walkforward 4h ETHUSDC

# Six-year multi-symbol research / optimizer report
clojure -M:research
clojure -M -m richbot.core research 4h 6 BTCUSDC,ETHUSDC,SOLUSDC,BNBUSDC

# Smart-DCA backtest: BTC, smart vs plain DCA vs lump sum
clojure -M:dca          # 1d candles, 6 years
clojure -M:dca 4h 6     # the parameters portfolio mode trades

# Stocks advisor (see STOCKS.md): ETF smart-DCA core + risky stock
# slots; sends BUY/SELL recommendations by Telegram, the user
# executes them manually in Revolut
clojure -M:stocks            # print the plan
clojure -M:stocks-research   # ETF DCA + stock walk-forward evidence
clojure -M:stocks-advisor    # run the advisor loop

# Dip scanner: portfolio-aware quality-watchlist discounts for
# EXTRA money (see STOCKS.md)
clojure -M:stocks-dips           # print the scored board
clojure -M:stocks-dips-scan      # scan once + Telegram (daily cron)
clojure -M:stocks-dips-status    # pending/executed dip trades

# Live testnet trading loop (needs .env, see below)
clojure -M:paper

# REAL-MONEY trading on binance.com (asks for confirmation; trades
# at most :live :capital from config, logs orders to trades-live.csv)
clojure -M:live

# REAL-MONEY multi-asset portfolio mode
clojure -M:portfolio-live yes
```

Configuration lives in `richbot.core/config`. Testnet credentials go in
`.env` (gitignored) — copy `.env.example` and fill in keys from
https://testnet.binance.vision.

## Strategies

- **`:donchian`** — channel breakout: buy when the close
  breaks above the highest high of the last `entry` candles, sell when
  it breaks below the lowest low of the last `exit` candles. Classic
  trend following: low win rate, but winners run and losers are cut.
- **`:filtered-donchian`** — Donchian breakout with a long-SMA regime
  filter and ATR/price volatility filter. It only buys breakouts when
  trend and volatility conditions are acceptable, but still sells on
  the Donchian exit.
- **`:sma-cross`** — fast/slow moving-average crossover with an
  anti-whipsaw band (the cross must clear the slow SMA by `band`).
- **`:rsi-revert`** — mean reversion: buy oversold (RSI below
  `buy-th`), sell overbought (above `sell-th`).
- **`:dca`** (portfolio slots only) — smart dollar-cost averaging:
  a base-size buy every `every` candles, multiplied by `tiers`
  when price trades at a discount to the `trend`-SMA (e.g. 2x at
  10% below, 3x at 25%, 5x at 40%). The slot budget is split into
  `buys` base buys; dips drain it faster. It never sells, so the
  drawdown stops do not apply — the budget is the exposure limit.

Every backtest fill pays 0.1% Binance taker fee plus 5 bps adverse
slippage. Live signals are computed on closed candles only.

## Validation results (June 2026)

Rolling walk-forward: params are optimized on a training window (by
Sharpe), then evaluated on the next unseen window. Only out-of-sample
results below; b&h = buy & hold over the same span.

| Interval | Strategy   | OOS return | Sharpe | maxDD | b&h (maxDD) |
|----------|------------|-----------:|-------:|------:|-------------|
| 1h BTC   | all three  | -25%..-36% |    neg |     — | +7.6%       |
| 4h BTC   | donchian   |     +59.0% |   0.81 | 32.8% | +105% (50%) |
| 4h BTC   | sma-cross  |     +37.9% |   0.61 | 31.9% | +105% (50%) |
| 4h ETH   | donchian   |      +5.8% |   0.24 | 48.0% | +4.6% (65%) |
| 4h ETH   | sma-cross  |     -62.1% |  -0.85 | 68.4% | +4.6% (65%) |
| 1d BTC   | donchian   |     -17.1% |      — |     — | +1.1%       |

Honest conclusions:

- **1h is dead**: fees and whipsaw eat everything.
- **Donchian on 4h is the only survivor on both assets** — positive
  out-of-sample on BTC and ETH, always with less drawdown than holding.
- **It does not beat buy & hold in raw return.** Its case is
  risk-adjusted: ~1/3 the market exposure and 2/3 the drawdown. That is
  a defensive bot, not a get-rich bot.
- sma-cross's BTC result did not replicate on ETH: treat it as luck.

The recommended mode is the portfolio (below): a BTC smart-DCA core
plus two risky trend slots. The single-symbol `:live` config is the
high-risk BNBUSDC sma-cross candidate on its own. Signal slots use
`:adaptive true`, which re-picks params on the trailing 1000 candles
instead of hand-freezing one optimized set.

## Research / optimizer

The `research` command tests multiple USDC pairs and every registered
strategy with rolling walk-forward validation over about six years by
default. It prints:

- out-of-sample return
- buy-and-hold return
- Sharpe
- max drawdown
- market exposure
- accept/reject reason
- capped allocation recommendation

Defaults:

```sh
clojure -M:research
```

Focused run:

```sh
clojure -M -m richbot.core research 4h 6 BTCUSDC,ETHUSDC,SOLUSDC,BNBUSDC
```

Candidates are rejected unless they pass the configured evidence rules:
positive out-of-sample return, Sharpe at least `0.35`, max drawdown no
more than `45%`, and exposure no more than `85%`. Allocation output is
a research recommendation. `portfolio-live` can run multiple configured
slots, but you should still review the evidence before enabling it.
See `RESEARCH-REPORT.md` for the latest local evidence run.

## Portfolio live mode

Portfolio mode runs multiple live slots with separate state and risk
limits. Current config (June 2026 research, see RESEARCH-REPORT.md):

```text
core-btc:  BTCUSDC, 50% weight, smart DCA (weekly buys, 2x/3x/5x
           sized at 10/25/40% below the ~200d SMA, never sells)
risky-bnb: BNBUSDC, 25% weight, sma-cross, 50% max drawdown
           (+1147% OOS over 6y walk-forward, Sharpe 1.32)
risky-sol: SOLUSDC, 25% weight, filtered-donchian, 50% max drawdown
           (+493% OOS, Sharpe 0.79)
```

Smart DCA backtests 27% more BTC accumulated than plain DCA over
the last six years for the same budget (avg cost 28.5k vs 36.2k);
on bull-only spans it ties. The risky slots are the two strongest
out-of-sample candidates with the drawdown rule relaxed to 50% —
they can and likely will draw down ~half their capital on the way
to any gains.

Print the plan:

```sh
clojure -M -m richbot.core portfolio
```

Run it live:

```sh
clojure -M:portfolio-live yes
```

State is stored in `.richbot-portfolio-state.edn`; equity snapshots go
to `equity-portfolio.csv`; executed orders still go to
`trades-live.csv`.

## Risk management

The live loop sizes positions to `:fraction` of available quote
balance and persists peak equity in `.richbot-state.edn`. If equity
drops more than `:max-drawdown` from its peak, it liquidates and stops
(kill switch survives restarts).

## Namespaces

- `richbot.binance` — market data (public) + signed testnet orders
- `richbot.config` — `.env` / environment loading
- `richbot.indicators` — SMA, rolling max/min, Wilder RSI
- `richbot.strategy` — signal generators + strategy registry
- `richbot.dca` — smart DCA: decision logic, backtest, comparison
  report (smart vs plain DCA vs lump sum)
- `richbot.stocks-data` — Yahoo Finance daily candles + FX rates
- `richbot.stocks` — stocks advisor loop: Telegram recommendations,
  manual execution in Revolut (see STOCKS.md)
- `richbot.stocks-research` — ETF DCA + stock walk-forward reports
- `richbot.dips` — daily quality-watchlist dip scanner: portfolio-
  aware scoring, Telegram digest, trade reconciliation against the
  Revolut CSV (see STOCKS.md)
- `richbot.backtest` — backtester: fees, slippage, Sharpe, profit
  factor, exposure, max drawdown
- `richbot.walkforward` — rolling walk-forward validation
- `richbot.paper` — live testnet loop: adaptive params, position
  sizing, kill switch
- `richbot.live` — real-money loop: startup confirmation, hard
  capital cap, exchange filters, 10% kill switch
- `richbot.tradelog` — append-only CSV of every executed order
- `richbot.core` — CLI entry point

## Documentation

- `HOW-IT-WORKS.md` — plain-language guide to what runs and why
- `SETUP.md` — full setup checklist, testnet → real money → VPS
- `VPS-OPERATIONS.md` — deploys (git), crontab, start/stop/restart
- `STOCKS.md` — stocks advisor + dip scanner manual
- `RESEARCH-REPORT.md` — latest crypto research evidence

## What this bot is not

It will not make you rich. It is machinery for testing ideas against
reality cheaply and for losing fake money instead of real money while
you learn. Months of profitable testnet trading is the minimum bar
before risking a single real euro — and no result here clears that bar
yet.
