# Stocks advisor (manual execution in Revolut)

The stocks module mirrors the crypto portfolio — a safe
accumulation core (world UCITS ETF, smart DCA) plus risky signal
slots picked by walk-forward evidence — but it places **no orders**.
It watches the market on daily candles (Yahoo Finance) and sends
each action as a Telegram message; you execute it manually in
Revolut. Internally it books every recommendation in a model
portfolio at market price, so budgets, signals and stops stay
consistent. Execute the recommended amounts to keep your real
account close to the model.

## Running it

Needs `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` in `.env`
(without them it only prints to console).

```sh
clojure -M:stocks            # print the plan
clojure -M:stocks-advisor    # run the advisor loop
```

On the VPS:

```sh
cd ~/richbot
nohup clojure -M:stocks-advisor >> stocks-advisor.log 2>&1 &
tail -f stocks-advisor.log
```

`:stocks :capital` is the first month's money and
`:stocks :contribution` is added to the model every month on the
first tick on/after `:pay-day` (set it just after your payday;
personal values go in `config.local.edn`, gitignored). The monthly
contribution splits across slots by weight, with a Telegram
reminder to fund the broker. With
recurring contributions the evidence says to invest the full
monthly base immediately: holding back a dip reserve lost 7-16%
over 20 years of SPY. Dips instead trigger **recharges**: optional
EXTRA deposits into VWCE (300/600/1200 EUR at 5/10/15% below the
200-day SMA, at most ~monthly). On 20y of SPY those discounts
averaged +15/+27/+36% one year later. Skip a recharge when you
have no spare cash — it is extra, never part of the plan.

## Messages you will receive

```text
ACCION RICHBOT STOCKS: COMPRA ~50.00 EUR de VWCE (Vanguard FTSE
All-World) en Revolut. Motivo: DCA semanal (precio 159.50 EUR)

ACCION RICHBOT STOCKS: COMPRA ~190.00 EUR de NVIDIA (NVDA) en
Revolut. Motivo: señal donchian de entrada (precio 208.19 USD)

ACCION RICHBOT STOCKS: VENDE ~210.00 EUR de NVIDIA (NVDA) en
Revolut. Motivo: señal donchian de salida / STOP drawdown 45%
```

Buy the named instrument for roughly the stated amount; sell means
close the whole position in that instrument. Recommendations below
10 EUR are not sent. Each is also logged to `advice-stocks.csv`.

## Current portfolio (June 2026 research)

```text
core-etf:   VWCE (FTSE All-World UCITS, EUR) 60% — monthly base
            buy + optional extra-money recharges on dips. Never
            sells.
risky-cost: COST 20% — donchian breakout, adaptive params.
            15y walk-forward: +223% OOS, Sharpe 0.81, maxDD 16.5%.
risky-tsla: TSLA 20% — sma-cross, adaptive params.
            15y walk-forward: +499% OOS, Sharpe 0.63, maxDD 52.9%.
```

The risky names were picked from a diversification universe
(`stocks-research-diversify.log`) **excluding everything already
held outside the system** (configure your holdings in
`config.local.edn` → `:dips :owned`). Candidates that scored
slightly better were rejected for overlapping existing mega-cap
tech and semiconductor exposure.

The model manages **new money** and ignores positions you already
hold in Revolut — your existing shares stay outside the system;
sell recommendations refer only to positions the advisor told you
to buy.

Research commands (raw log: `stocks-research-run.log`):

```sh
clojure -M:stocks-dca        # ETF DCA tier comparison
clojure -M:stocks-research   # both reports
```

Key evidence:

- On 20 years of SPY (dividends reinvested) the shallow-tier smart
  DCA returned +424% vs +360% plain DCA, accumulating 13.8% more
  shares. The dip-boost matters less than in crypto because index
  discounts below the 200d SMA are rare — it is asymmetric upside
  for bear markets, near-free otherwise.
- 9 stocks x 4 strategies x 25 walk-forward windows over 15 years:
  NVDA donchian and GOOGL sma-cross were the strongest accepted
  candidates under risk rules (Sharpe >= 0.35, maxDD <= 55%).

## Dip scanner (extra money)

A daily cron job (`clojure -M:stocks-dips-scan`, see
`VPS-OPERATIONS.md`) scans a ~25-name quality watchlist for prices
at least 5% below their 200-day SMA and Telegrams the best uses of
the next EXTRA money. It never touches the monthly plan.

It is portfolio-aware: it reads your Revolut transaction export
(`resources/portfolio.csv`, gitignored; path in
`REVOLUT_STOCKS_CSV`) and shrinks or reclassifies suggestions for
names you already hold (`recharge`), crowded mega-tech/AI/semis
exposure (`crowded`), and oversized positions. Deep discounts with
a very bad trailing year (possible value traps) are rejected, not
recommended. Suggestions are capped by a monthly opportunity budget
(300 EUR) and skip correlated names within the same digest.

Each suggested buy is stored as a pending trade. The scanner
reconciles against the Revolut CSV: when a matching BUY appears
there, the trade is marked executed and the scanner starts watching
its review levels (back at the SMA200, +15/20% from entry, or -10%
risk check) and alerts when one triggers. Until then it sends
pending-execution reminders. `clojure -M:stocks-dips-status` prints
the current trade list.

Optional: a `fundamentals.csv` (template via
`clojure -M:stocks-dips-fundamentals-template`) overrides
per-symbol quality/valuation scores and adds PE, FCF yield, revenue
growth and EPS revisions, which adjust the score and the trap
filter. Without it the scanner uses its built-in defaults.

```sh
clojure -M:stocks-dips           # print the full scored board now
clojure -M:stocks-dips-scan      # scan once + Telegram (cron entry)
clojure -M:stocks-dips-status    # pending/executed dip trades
clojure -M:stocks-dips-backtest  # sanity-check the dip rule
```

## Quarterly review

Strategy *parameters* adapt automatically (adaptive walk-forward
re-optimization on trailing data, every tick). The *names* do not:
a VPS cron re-runs the full research on Jan/Apr/Jul/Oct 1st
(`quarterly-research.sh`, broad universe + crypto) and sends the
ACCEPT summary by Telegram. Slots are then changed manually, and
only when one of these holds:

1. The slot stopped out (drawdown/daily stop fired).
2. Its symbol+strategy no longer passes the rules on refreshed
   data (negative OOS, Sharpe < 0.35, or drawdown above limit).
3. A candidate dominates it on **both** return and drawdown,
   **and** swapping does not increase concentration with holdings
   outside the system (e.g. in June 2026 AMD dominated TSLA but
   stacked semiconductor risk on existing positions, so TSLA
   stayed).

Otherwise hold: swapping winners chases recency, and every swap is
churn plus a taxable event. Beware window noise: re-running the
research one day apart moved several OOS numbers by 10-100%+
without any new information — never act on a single refresh.

## Honest caveats

- **You are the execution engine.** The model assumes you act near
  the recommended price. Acting a day late on a stop or skipping a
  sell makes your real account diverge from the model — if that
  happens, adjust your position to match the recommendation as
  soon as you see it.
- **Survivorship bias**: NVDA and GOOGL are *the* winners of the
  last 15 years and were picked by looking at that same period.
  The next 15 years will not look like that. Expect much less.
- **Revolut costs** (FX markup on USD trades, possible commissions
  past the free-trade allowance) are not in the backtests.
- **Taxes (Spain)**: every sell recommendation you execute is a
  taxable event (FIFO). The DCA core never sells. Keep
  `advice-stocks.csv` and your Revolut statements.

## Files

- state (model portfolio): `.richbot-stocks-state.edn`
- recommendations: `advice-stocks.csv`
- equity snapshots: `equity-stocks.csv`
