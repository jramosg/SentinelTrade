# Swing alerts (manual execution in Revolut)

The swing monitor watches an explicit, dated stock setup on Yahoo Finance
five-minute candles and sends each state transition to Telegram. It does not
have broker credentials and cannot buy or sell anything.

The public defaults contain no active trade. Dated setups belong in the
gitignored `config.local.edn`, so cloning the project can never publish or
activate somebody else's advice. A setup can define:

- a symbol, manual capital allocation, currency, and venue
- entry dates and an exchange-local time window
- acceptable session-open and live-entry ranges
- a trigger and number of completed confirmation candles
- a thesis-invalidating stop and one or more partial targets
- a final time exit for any remaining position

The setup is data, not hard-coded logic. Add `:swing-alerts` to
`config.local.edn` to monitor a trade without changing source code. The bot
validates symbols, dates, entry ordering, stops, targets, and total exit size
before making a network request.

Start from [`config.example.edn`](config.example.edn), merge the relevant
section into `config.local.edn`, replace every placeholder, and only then set
`:enabled? true`.

## Telegram configuration

Add these values to `.env`:

```env
TELEGRAM_BOT_TOKEN=<telegram-bot-token>
TELEGRAM_CHAT_ID=<telegram-chat-id>
```

Create a bot with Telegram's `@BotFather`, send the new bot a message, and get
the chat ID from:

```text
https://api.telegram.org/bot<TELEGRAM_BOT_TOKEN>/getUpdates
```

Never commit `.env`; it is already ignored by Git.

## Commands

```sh
scripts/swing_alerts.sh status
scripts/swing_alerts.sh once
scripts/swing_alerts.sh start
scripts/swing_alerts.sh stop
```

The wrapper always changes to the repository directory before invoking Clojure.
Its detached process reads from `/dev/null`, preventing a background REPL from
repeatedly failing with `Bad file descriptor`. The long-running command polls
once a minute. Yahoo data can be delayed, so entry confirmation uses completed
candles and accepts at most 30 minutes of data lag. Stops and targets use the
most recent forming candle to reduce delay.

State is saved in `.richbot-swing-alerts-state.edn`; sent events are appended
to `swing-alerts-events.log`. Both files are ignored by Git. Restarting the
process therefore does not repeat an entry, target, stop, skip, or expiry
message.

## Run on the VPS

```sh
cd ~/richbot
scripts/swing_alerts.sh start
tail -f swing-alerts.log
```

Stop it with:

```sh
scripts/swing_alerts.sh stop
```

To start it after a reboot, add this separately to the VPS crontab after you
have reviewed the active setup:

```cron
@reboot /root/richbot/scripts/swing_alerts.sh start
```

Do not delete the state file while following an alerted position. If you did
not execute an entry alert, disable that setup or deliberately reset its state
before reusing it.
