# How the bot works

A plain-language guide. No programming or trading background assumed.

## What runs in production

Four things run on the VPS:

1. **Crypto portfolio (`portfolio-live`)** — trades real money on
   Binance with three slots: a BTC smart-DCA core (50%) that buys on
   a schedule and buys more when price is far below its long-term
   average, plus two trend-following risky slots, BNB (25%) and
   SOL (25%). Pairs are vs USDC.
2. **Stocks advisor (`stocks-advisor`)** — watches VWCE, COST and
   TSLA on daily candles and sends BUY/SELL recommendations by
   Telegram. It places no orders: you execute them manually in
   Revolut. See `STOCKS.md`.
3. **Dip scanner (cron, `stocks-dips-scan`)** — once a day scans a
   ~25-name quality watchlist for prices well below their 200-day
   average and Telegrams the best uses of EXTRA money. See
   `STOCKS.md`.
4. **Quarterly research (cron)** — re-runs the full strategy
   research every Jan/Apr/Jul/Oct 1st and Telegrams the summary.

## How often it decides

The crypto bot uses 4-hour candles; the stocks advisor uses daily
candles. Both decide only on **closed** candles — never on data that
is still changing. That is why they can sit for hours or days doing
nothing.

## What HOLD means

```text
HOLD price 3500.0 equity 1000.00
```

The bot is alive, looked at the market, and saw no clear signal.
HOLD is not an error — it is "wait", and it is the normal state most
of the time.

## When it buys and sells

- **DCA slots** (BTC core, VWCE) buy a base amount on schedule and
  multiply the buy when price is at a deep discount to the long
  trend average. They never sell — the budget is the limit.
- **Signal slots** (BNB, SOL, COST, TSLA) buy when their
  trend-following strategy fires an entry (e.g. price breaks above
  a recent range) and sell on the exit signal.
- **Protective stops** (signal slots only): if a slot's equity falls
  more than its configured max drawdown from its peak, or more than
  the daily-loss limit since the start of the UTC day, it liquidates
  and stops. A stopped slot stays stopped across restarts (the peak
  is persisted in the state file) — it cannot resume by accident.

## What money it touches

The crypto bot only manages its configured `:capital` (see
`richbot.core/config`), even if the Binance account holds more. Each
slot gets its weight of that capital. The stocks advisor manages a
*model* portfolio of new money only; your pre-existing Revolut
holdings stay outside the system.

## Important files (on the VPS, `~/richbot`)

| File | What it is |
|---|---|
| `portfolio-live.log` | main crypto log (HOLD/BUY/SELL per tick) |
| `stocks-advisor.log` | stocks advisor log |
| `trades-live.csv` | every executed real order — **fiscal record, never delete** |
| `equity-*.csv` | account snapshot per tick |
| `advice-stocks.csv` | every recommendation the advisor sent |
| `.richbot-*.edn` | internal state (cash, qty, peaks, kill switch) |
| `.env` | API keys + Telegram credentials — never in git |

If you change configured capital, review (or delete, if the bot has
no open position) the matching state file before restarting —
otherwise the bot resumes with stale accounting.

## How to know it is healthy

The log shows a startup banner with the configured capital and risk
limits, then `params {...}` (the strategy parameters it picked) and
a HOLD/BUY/SELL line every tick. If Telegram is configured you also
get a startup message and one message per action, stop or error.

What it does **not** mean:

- The bot being on does not mean it will buy soon.
- No trades yet does not mean it is broken.
- HOLD does not mean it is stuck.

## The loop, ultra-short

```text
1. Fetch closed candles.
2. Compute the signal.
3. BUY / SELL if the signal says so, sized by the slot's budget.
4. Otherwise wait.
5. If losses exceed the configured stops, liquidate and stop.
```
