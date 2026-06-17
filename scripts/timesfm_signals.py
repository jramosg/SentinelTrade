#!/usr/bin/env python3
"""
Run TimesFM (or EWM fallback) on the dip universe and write
resources/timesfm_signals.json for richbot/dips.clj to consume.

Usage:
  python scripts/timesfm_signals.py [--horizon 21] [--out resources/timesfm_signals.json]

TimesFM needs ~800 MB RAM — runs locally, then rsync the JSON to VPS:
  rsync -av resources/timesfm_signals.json $VPS_USER@$VPS_HOST:~/richbot/resources/
"""

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import yfinance as yf

# Dip universe: richbot symbol -> Yahoo Finance ticker
SYMBOL_MAP = {
    "AAPL":    "AAPL",
    "ADBE":    "ADBE",
    "AMZN":    "AMZN",
    "ASML":    "ASML",
    "AVGO":    "AVGO",
    "COST":    "COST",
    "CRM":     "CRM",
    "GOOG":    "GOOG",
    "HD":      "HD",
    "ITX.MC":  "ITX.MC",
    "JPM":     "JPM",
    "LLY":     "LLY",
    "MA":      "MA",
    "MC.PA":   "MC.PA",
    "MELI":    "MELI",
    "META":    "META",
    "MSFT":    "MSFT",
    "NFLX":    "NFLX",
    "NKE":     "NKE",
    "NVO":     "NVO",
    "NVDA":    "NVDA",
    "SAP":     "SAP",
    "TSM":     "TSM",
    "UBER":    "UBER",
    "UNH":     "UNH",
    "V":       "V",
    "VWCE.DE": "VWCE.DE",
}


def download_close(yf_ticker: str, period: str = "5y") -> np.ndarray:
    data = yf.download(
        yf_ticker, period=period, interval="1d",
        auto_adjust=True, progress=False,
    )
    if data.empty:
        raise ValueError(f"No data for {yf_ticker}")
    close = data["Close"].dropna()
    if hasattr(close, "iloc") and close.ndim > 1:
        close = close.iloc[:, 0]
    return close.values.astype(np.float64)


def ewm_forecast(close: np.ndarray, horizon: int = 21) -> dict:
    """Momentum heuristic when TimesFM is unavailable."""
    if len(close) < 22:
        return {"return": 0.0, "q_low": -0.10, "q_high": 0.10, "mode": "ewm"}
    # 21-day realized momentum, damped by 0.4 (momentum factor)
    recent_ret = float(close[-1] / close[-22] - 1.0)
    expected = recent_ret * 0.4
    std = float(np.std(np.diff(np.log(close[-63:]))) * np.sqrt(horizon))
    return {
        "return": round(expected, 6),
        "q_low":  round(expected - 1.28 * std, 6),
        "q_high": round(expected + 1.28 * std, 6),
        "mode": "ewm",
    }


def timesfm_forecast(model, close: np.ndarray, horizon: int) -> dict:
    log_close = np.log(close.astype(np.float32))
    context = log_close[-1024:]
    point, quantiles = model.forecast(horizon=horizon, inputs=[context])
    current_log = float(log_close[-1])
    forecast_log = float(point[0, -1])
    expected = float(np.exp(forecast_log - current_log) - 1.0)
    q = quantiles[0, -1]
    if len(q) >= 10:
        q_low_log, q_high_log = float(q[1]), float(q[-1])
    elif len(q) >= 2:
        q_low_log, q_high_log = float(q[0]), float(q[-1])
    else:
        q_low_log = q_high_log = forecast_log
    return {
        "return": round(expected, 6),
        "q_low":  round(float(np.exp(q_low_log - current_log) - 1.0), 6),
        "q_high": round(float(np.exp(q_high_log - current_log) - 1.0), 6),
        "mode": "timesfm",
    }


def load_model(horizon: int):
    import torch
    import timesfm
    torch.set_float32_matmul_precision("high")
    model = timesfm.TimesFM_2p5_200M_torch.from_pretrained(
        "google/timesfm-2.5-200m-pytorch"
    )
    model.compile(
        timesfm.ForecastConfig(
            max_context=1024,
            max_horizon=max(horizon, 32),
            normalize_inputs=True,
            use_continuous_quantile_head=True,
            force_flip_invariance=True,
            infer_is_positive=True,
            fix_quantile_crossing=True,
        )
    )
    return model


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--horizon", type=int, default=21)
    parser.add_argument("--out", default="resources/timesfm_signals.json")
    args = parser.parse_args()

    try:
        print("Loading TimesFM model...", flush=True)
        model = load_model(args.horizon)
        use_timesfm = True
        print("TimesFM loaded.", flush=True)
    except Exception as e:
        print(f"TimesFM unavailable ({e}), using EWM fallback.", flush=True)
        model = None
        use_timesfm = False

    # `_`-prefixed keys are metadata, not tickers — the Clojure
    # consumer looks up real symbols only, so these never collide.
    signals = {
        "_generated_at": datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "_horizon": args.horizon,
    }
    for symbol, yf_ticker in SYMBOL_MAP.items():
        try:
            close = download_close(yf_ticker)
            if use_timesfm:
                sig = timesfm_forecast(model, close, args.horizon)
            else:
                sig = ewm_forecast(close, args.horizon)
            signals[symbol] = sig
            mode = sig["mode"]
            ret = sig["return"]
            print(f"  {symbol:10s} {ret:+.2%}  [{mode}]", flush=True)
        except Exception as e:
            print(f"  {symbol:10s} SKIP: {e}", flush=True)

    n_signals = sum(1 for k in signals if not k.startswith("_"))
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(signals, f, indent=2)
    print(f"\nWrote {n_signals} signals → {out_path}")
    vps_user = os.environ.get("VPS_USER", "root")
    vps_host = os.environ.get("VPS_HOST", "<VPS_HOST>")
    print(
        "\nTo push to VPS:\n"
        f"  rsync -av {out_path} "
        f"{vps_user}@{vps_host}:~/richbot/resources/timesfm_signals.json"
    )


if __name__ == "__main__":
    main()
