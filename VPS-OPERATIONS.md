# VPS operations

How to deploy, start, stop, watch and restart everything on the VPS.
The project lives in `~/richbot`.

## Deploying changes (git)

The repo is the deploy unit. Secrets, state, logs and personal CSVs
are gitignored, so a pull can never touch them.

```sh
# on your machine
git push

# on the VPS
cd ~/richbot && git pull
```

Then restart whichever long-running process the change affects (see
below). Cron-invoked commands (dip scan, quarterly research) pick up
new code automatically on their next run.

Private files are NOT in git and are created or copied by hand.
`config.local.edn` holds the personal config (capital,
contribution, pay day, owned holdings) and is deep-merged over
`richbot.core/base-config` at startup:

```edn
{:live {:capital 1234.0}
 :portfolio {:capital 1234.0}
 :stocks {:capital 1000.0 :contribution 1000.0 :pay-day 7}
 :dips {:owned #{"MSFT" "VWCE.DE"}}}
```

The other two are copied when they change:

```sh
# Revolut transaction export (lets the dip scanner see your real
# portfolio and detect executed buys)
rsync -av portfolio.csv root@<VPS-IP>:~/richbot/resources/portfolio.csv

# optional manual fundamentals for the dip scorer
rsync -av fundamentals.csv root@<VPS-IP>:~/richbot/fundamentals.csv
```

`.env` is created once, by hand, on the VPS (`chmod 600 .env`).

## Crontab

`crontab -e` should contain:

```cron
# daily dip scan, weekdays pre-dawn UTC (markets closed, daily
# candles complete, low server load; live price = last close)
30 3 * * 1-5 cd /root/richbot && . ./.env && clojure -M:stocks-dips-scan >> /root/richbot/stocks-dips.log 2>&1

# quarterly research review (Jan/Apr/Jul/Oct 1st, overnight)
30 4 1 1,4,7,10 * /root/richbot/quarterly-research.sh >> /root/richbot/quarterly-cron.log 2>&1

# watchdog: restart + Telegram if a process died
# (touch ~/richbot/.maintenance to pause it during manual work)
*/10 * * * * /root/richbot/watchdog.sh >> /root/richbot/watchdog.log 2>&1

# weekly digest: equity/positions/drawdown by Telegram, Sundays
0 18 * * 0 cd /root/richbot && /usr/local/bin/clojure -M:report >> report.log 2>&1

# survive VPS reboots
# portfolio-live (crypto) intentionally has NO @reboot line since
# 2026-07-01 - stopped after repeated Binance 401s, see watchdog.sh.
# Add it back only once Binance API access is confirmed working:
#   @reboot cd /root/richbot && nohup /usr/local/bin/clojure -M:portfolio-live yes >> portfolio-live.log 2>&1 &
@reboot cd /root/richbot && nohup /usr/local/bin/clojure -M:stocks-advisor >> stocks-advisor.log 2>&1 &
```

## The long-running processes

### Crypto portfolio (real money)

```text
core-btc:  BTCUSDC 50% (smart DCA: scheduled buys, bigger on deep
           dips, never sells)
risky-bnb: BNBUSDC 25% (sma-cross, 50% drawdown stop)
risky-sol: SOLUSDC 25% (filtered-donchian, 50% drawdown stop)
```

```sh
cd ~/richbot
clojure -M -m richbot.core portfolio                      # print plan
nohup clojure -M:portfolio-live yes >> portfolio-live.log 2>&1 &   # start
pkill -f "richbot.core portfolio[-]live"                  # stop
pgrep -af "portfolio-live"                                # check
```

`yes` on the command line is the live-trading confirmation; without
it the process asks to type `SI` and cannot start unattended.

Never run `live` and `portfolio-live` at the same time on the same
account, and never start a second copy: check with `pgrep` first.
(The `[-]` in the pkill pattern stops pkill from matching itself.)

### Stocks advisor (Telegram recommendations, no orders)

```sh
cd ~/richbot
nohup clojure -M:stocks-advisor >> stocks-advisor.log 2>&1 &   # start
pkill -f "richbot.core stocks[-]advisor"                       # stop
tail -f stocks-advisor.log                                     # watch
```

## After deploying, what to restart

| What changed | Restart |
|---|---|
| crypto code/config (`live.clj`, `binance.clj`, crypto slots) | `portfolio-live` |
| stocks advisor code/config (`stocks.clj`, `dca.clj`, stocks slots) | `stocks-advisor` |
| dip scanner (`dips.clj`, `:dips` config) | nothing — next cron run uses it |
| research code, `quarterly-research.sh` | nothing — next cron run uses it |
| docs | nothing |

## Watching

```sh
tail -f ~/richbot/portfolio-live.log
tail -f ~/richbot/stocks-advisor.log
tail -20 ~/richbot/stocks-dips.log
column -s, -t ~/richbot/trades-live.csv      # executed real orders
column -s, -t ~/richbot/advice-stocks.csv    # sent recommendations
column -s, -t ~/richbot/equity-portfolio.csv # crypto equity per tick
grep -c ERROR ~/richbot/portfolio-live.log   # accumulated errors
```

## State files

```sh
cat ~/richbot/.richbot-portfolio-state.edn   # crypto slots
cat ~/richbot/.richbot-stocks-state.edn      # stocks model portfolio
cat ~/richbot/.richbot-dips-state.edn        # dip alerts/trades/budget
```

Do not delete a state file while the bot holds a position unless you
know exactly what accounting you are rebuilding. If a kill switch
fired and cron relaunches the bot after a reboot, the persisted peak
makes it stop again immediately — it cannot resume trading by
accident. To genuinely re-arm it: investigate why it fired, delete
the state file, relaunch by hand.

`trades-live.csv` is the fiscal record of every real order — never
delete it.

## Environment variables

In `~/richbot/.env` (see `.env.example` for the full list):

```env
BINANCE_LIVE_KEY=...
BINANCE_LIVE_SECRET=...
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_ID=...
REVOLUT_STOCKS_CSV=resources/portfolio.csv
```

Telegram is optional for the crypto bot (it trades the same without
it) but pointless to skip for the stocks advisor and dip scanner —
recommendations are their only output.

## Telegram alerts you will get

- live start / BUY / SELL executions (crypto)
- kill switch and daily-loss stops
- errors, and a final message if a loop stops after repeated errors
- stocks advisor: BUY/SELL recommendations, monthly contribution
  reminder, VWCE dip recharges
- dip scanner: daily digest when something on the watchlist is at a
  qualifying discount, pending-execution reminders, review alerts

## systemd alternative

`nohup` + `@reboot` works. If you prefer systemd:

```ini
# /etc/systemd/system/richbot-portfolio.service
[Unit]
Description=Richbot portfolio live trading
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/root/richbot
ExecStart=/usr/bin/clojure -M:portfolio-live yes
Restart=on-failure
RestartSec=30
StandardOutput=append:/root/richbot/portfolio-live.log
StandardError=append:/root/richbot/portfolio-live.log

[Install]
WantedBy=multi-user.target
```

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now richbot-portfolio
sudo systemctl status richbot-portfolio
```

If you adopt systemd, remove the matching `@reboot` cron line so two
copies never start.
