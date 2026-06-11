# Research report (June 2026)

Generated with:

```sh
clojure -M -m richbot.core research 4h 6 \
  BTCUSDC,ETHUSDC,SOLUSDC,BNBUSDC,LINKUSDC,AVAXUSDC
clojure -M:dca 1d 6
clojure -M:dca 4h 6
```

## Walk-forward research (4h, 6 years, rolling out-of-sample)

Full grid (regenerate with `clojure -M:research`; the quarterly
cron refreshes `quarterly-research.log`):

| Symbol | Strategy          | OOS return | Sharpe | maxDD | b&h     |
|--------|-------------------|-----------:|-------:|------:|---------|
| BNB    | sma-cross         |   +1147.5% |   1.32 | 54.1% | +1780%  |
| BNB    | filtered-donchian |    +789.6% |   1.10 | 46.8% | +1780%  |
| SOL    | filtered-donchian |    +493.4% |   0.79 | 46.6% | +3.0%   |
| SOL    | sma-cross         |    +275.3% |   0.69 | 46.9% | +3.0%   |
| BTC    | sma-cross         |    +198.1% |   0.79 | 58.4% | +284%   |
| BTC    | filtered-donchian |    +128.3% |   0.65 | 45.5% | +284%   |
| ETH    | filtered-donchian |     +96.2% |   0.53 | 44.2% | +176%   |
| LINK   | (best)            |     +23.9% |   0.37 | 54.2% | -64.2%  |
| AVAX   | (best)            |      +8.0% |   0.32 | 45.4% | -67.7%  |

Only ETHUSDC filtered-donchian passes the conservative rules
(maxDD <= 45%). LINK and AVAX are weak across every strategy and
are excluded entirely.

The risky sleeve deliberately relaxes the drawdown rule to 50%:
BNB sma-cross and SOL filtered-donchian are the two strongest
out-of-sample candidates, diversified across symbol and strategy.
Both have ~46-54% historical drawdown — risk capital only.

## DCA research (BTCUSDC)

Smart DCA buys a base amount on schedule and multiplies the buy
(2x/3x/5x) when price is 10/25/40% below the ~200-day SMA. Same
total budget as plain DCA; idle cash counts in equity.

6 years, 1d candles, weekly base buys:

| Variant   | BTC accumulated | Avg cost | Return  | maxDD |
|-----------|----------------:|---------:|--------:|------:|
| smart-dca |        0.351322 |   28,464 | +118.8% | 51.2% |
| plain-dca |        0.276242 |   36,200 |  +72.0% | 50.3% |
| lump-sum  |        1.077224 |    9,283 | +570.7% | 72.7% |

The 4h variant (every 42 candles, trend SMA 1200 — the live
config) reproduces the same numbers (+119.4% vs +72.4%).

Conclusions:

- Smart DCA accumulated **27% more BTC** than plain DCA for the
  same budget, at a 21% lower average cost.
- On the last 3 bull-only years smart and plain DCA are a wash
  (+2.5% vs +3.4%): the boost only pays when deep discounts occur.
  Asymmetric benefit, negligible downside.
- Lump sum beats any DCA in raw return over a rising span — but it
  is not comparable when capital arrives over time, and it ate a
  73% drawdown.

## Portfolio decision

```text
core-btc:  BTCUSDC 50%, :dca every 42 (weekly), trend 1200 (~200d),
           tiers 10%->2x 25%->3x 40%->5x, budget split in 12 buys.
           Never sells, no drawdown stop (the budget is the limit).
risky-bnb: BNBUSDC 25%, sma-cross adaptive, max-dd 50%, daily 25%.
risky-sol: SOLUSDC 25%, filtered-donchian adaptive, max-dd 50%,
           daily 25%.
```

Keep `capital * weight / :buys` above Binance's ~5 USDC min
notional, and increase `:buys` only if capital grows accordingly.

## Important

Past out-of-sample performance is evidence, not a guarantee. The
risky sleeve's expected drawdowns are near 50% — its slots stop
and liquidate at -50% from peak. The DCA core can sit at large
unrealized drawdowns by design and never sells.
