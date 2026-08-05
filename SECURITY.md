# Security policy

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for security issues. Do not open
a public issue containing credentials, account identifiers, private portfolio
data, server addresses, or exploit details that would put an active deployment
at risk.

If a secret has been exposed, revoke and replace it immediately. Removing it
from the latest commit is not sufficient because Git history retains it.

## Deployment baseline

- Keep `.env`, `config.local.edn`, broker exports, state, and logs untracked.
- Give exchange API keys trading permission only; never enable withdrawals.
- Restrict live keys to the VPS IP and use a separate testnet key.
- Treat Telegram tokens as credentials and rotate them after exposure.
- Run stock alerts without broker credentials; execution remains manual.

This project is experimental trading software. A valid security report does
not imply that a strategy is profitable or suitable for real money.
