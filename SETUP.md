# richbot — setup guide

Complete checklist, in order. Phases 1–3 work today with no keys and
no risk; phase 4 is real money.

---

## Phase 0 — Requirements

- [ ] Clojure CLI installed (`clojure -version`)
- [ ] Java 11+ (`java -version`)
- [ ] Repo cloned and you are inside it

---

## Phase 1 — Validate the strategy (no keys, no risk)

Uses only public Binance data. No account needed.

```sh
# Backtest the default (donchian 4h, ~16 months of BTCUSDC)
clojure -M:backtest

# Full walk-forward validation (the serious test)
clojure -M:walkforward 4h
clojure -M -m richbot.core walkforward 4h ETHUSDC
```

What to look at:

- **TOTAL oos** — compounded out-of-sample return. The only number
  that matters; in-sample returns always lie.
- **sharpe** — below ~0.5 is noise; 0.8+ starts to be signal.
- **maxDD** — always compare against buy & hold's maxDD.

Status (June 2026): donchian 4h is the only strategy positive
out-of-sample on both BTC and ETH. It does not beat buy & hold in
raw return; its merit is doing it with 1/3 the exposure and 2/3 the
drawdown.

---

## Phase 2 — Connect the testnet (fake money, real orders)

1. Go to <https://testnet.binance.vision> and sign in with GitHub
2. Click **Generate HMAC-SHA-256 Key**, name it `richbot`
3. Copy the API key and secret **right then** (the secret is shown
   only once)
4. Create `.env` (gitignored, never committed):

```sh
cp .env.example .env
# edit .env:
#   BINANCE_TESTNET_KEY=your_api_key
#   BINANCE_TESTNET_SECRET=your_secret
```

5. Start the bot:

```sh
clojure -M:paper
```

You will see something like this every minute:

```text
Testnet trading BTCUSDC 4h | strategy :donchian | max-dd 0.15 | size 0.95
2026-06-10T10:44:49Z params {:entry 40, :exit 20}
2026-06-10T10:44:49Z HOLD price 61687.56 USDC 10000.00 base 1.000000
```

- `params` — what adaptive mode picked
- `HOLD/BUY/SELL` — the per-tick decision (signals can only change
  at each 4h candle close, so HOLD is normal)
- The kill switch liquidates and stops the bot if equity falls
  beyond the configured drawdown from its peak (the peak persists in
  `.richbot-state.edn` across restarts; delete it to reset)

To leave it running for days:

```sh
nohup clojure -M:paper >> paper.log 2>&1 &
tail -f paper.log
```

---

## Phase 3 — The step almost everyone skips: time

Leave the testnet running for **weeks** and compare what it does
against what walk-forward predicted. That difference is the "reality
cost" (real slippage, missed candles, restarts) and no backtest
gives it to you.

Minimum honest bar before thinking about real money:

- [ ] 4–8 weeks of uninterrupted testnet
- [ ] Results consistent with the validation (it does not need to
      win big; it needs to not deviate inexplicably)
- [ ] Zero operational errors (rejected orders, crashes,
      inconsistent state)

---

## Phase 4 — Real money (small amount)

Implemented (`clojure -M:live` / `clojure -M:portfolio-live`) with
these protections:

- Explicit confirmation at startup (type `SI`, or pass `yes` on the
  command line for unattended starts)
- Exchange filters: quantities rounded to `stepSize`, minimum
  notional respected (~5 USDC), read from `exchangeInfo`
- **Hard capital cap**: the bot trades only `:capital` (see
  `richbot.core/config`) even if the account holds more; it keeps
  its own accounting in the state file
- Kill switch at the configured max drawdown (liquidates and stops;
  survives restarts)
- **CSV log of every order** in `trades-live.csv` (timestamp, side,
  qty, average price, amount, order-id); the testnet writes its own
  to `trades-testnet.csv`

### Your steps

1. [ ] binance.com account with KYC completed
2. [ ] Transfer a small amount — **money whose total loss you can
       shrug off** — and convert it to USDC
3. [ ] Create an API key under *Account → API Management*:
   - ✅ **Enable Spot Trading** (the only permission)
   - ❌ **NO withdrawal permission** — non-negotiable: if the key
     leaks, nobody can move funds out
   - ✅ IP restriction (the VPS IP)
4. [ ] Add to `.env` (keep the testnet keys too):

```text
BINANCE_LIVE_KEY=...
BINANCE_LIVE_SECRET=...
```

5. [ ] Set `:live :capital` (or the portfolio `:capital`) in
       `src/richbot/core.clj` to your real amount and start:

```sh
clojure -M:portfolio-live     # asks for SI before trading
```

### Taxes (Spain)

Every profitable sell is a **capital gain** that goes in the income
tax return. A bot generates many events. Keep both records:

- `trades-live.csv` — written automatically by the bot
- The official Binance history (*Orders → Trade History*) — export
  it periodically as backup

---

## Phase 5 — Where to run it: a VPS (recommended)

Your PC changes IP and sleeps. That rules out the two things you
want: the **IP allowlist** on the API key (the best protection if
the key leaks) and the **continuous uptime** a bot needs.

A basic VPS is plenty (1 vCPU, 2 GB RAM): Hetzner, Netcup, OVH,
DigitalOcean, or Oracle Cloud free tier. Ubuntu 24.04.

### Prepare the VPS

```sh
# 1. Java + Clojure
sudo apt update && sudo apt install -y openjdk-21-jre-headless
curl -LO https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh && sudo ./linux-install.sh

# 2. Clone the repo (deploys are then just `git pull`)
git clone <YOUR-PRIVATE-REMOTE-URL> ~/richbot

# 3. Create .env ON the VPS (never copy it over insecure channels)
cd ~/richbot && nano .env   # paste the keys
chmod 600 .env

# 4. Create config.local.edn with your capital, contribution,
#    pay day and holdings (format in VPS-OPERATIONS.md)
nano config.local.edn
```

See `VPS-OPERATIONS.md` for deploys, the crontab, and start/stop
commands for each process.

### IP allowlist

With the VPS you have a fixed IP: edit the live API key in Binance
(*API Management → Edit restrictions*) and add the VPS IP under
*Restrict access to trusted IPs only*. From then on the key works
only from your VPS.

---

## Reference commands

```sh
clojure -M:backtest                                # default backtest
clojure -M:walkforward [1h|4h|1d]                  # BTC validation
clojure -M -m richbot.core walkforward 4h ETHUSDC  # other pair
clojure -M:paper                                   # live testnet
clojure -M:portfolio-live                          # REAL MONEY (asks SI)
clojure -M:stocks-advisor                          # stocks advisor loop
clojure -M:stocks-dips-scan                        # one dip scan
clj-kondo --lint src                               # lint
```

Configuration: `src/richbot/core.clj` → `config`.

---

## Final rule

If the real bot ever loses more than walk-forward said the maximum
expected drawdown was, that is not bad luck: either the market
changed or the validation was optimistic. Shut it down, go back to
testnet, re-validate. A bot can recover from a drawdown; your
account does not recover from "it will bounce back".
