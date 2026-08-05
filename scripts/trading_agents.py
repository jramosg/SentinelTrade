#!/usr/bin/env python3
"""Run the sibling TradingAgents project as a Richbot research layer.

This adapter deliberately stops at producing research artifacts. It never
places orders, changes Richbot state, or sends recommendations to Telegram.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any


BOT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REPO = BOT_ROOT.parent / "TradingAgents"
DEFAULT_RUNTIME_DIR = BOT_ROOT / ".richbot-trading-agents"
DEFAULT_REPORTS_DIR = BOT_ROOT / "reports" / "trading-agents"
ANALYSTS = ("market", "social", "news", "fundamentals")
CRYPTO_SUFFIXES = ("-USD", "-USDT", "-USDC", "-BTC", "-ETH")
TICKER_PATTERN = re.compile(r"^[A-Za-z0-9._\-^=]{1,32}$")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run TradingAgents for research only and save an auditable report."
        )
    )
    parser.add_argument("ticker", nargs="?", help="Yahoo ticker, e.g. COST or BTC-USD")
    parser.add_argument(
        "--date",
        default=str(date.today()),
        help="analysis date in YYYY-MM-DD format (default: today)",
    )
    parser.add_argument(
        "--repo",
        type=Path,
        default=Path(os.getenv("TRADINGAGENTS_REPO", DEFAULT_REPO)),
        help="local TradingAgents checkout (default: ../TradingAgents)",
    )
    parser.add_argument(
        "--runtime-dir",
        type=Path,
        default=DEFAULT_RUNTIME_DIR,
        help="cache, checkpoints, state logs, and decision history",
    )
    parser.add_argument(
        "--reports-dir",
        type=Path,
        default=DEFAULT_REPORTS_DIR,
        help="directory for readable report trees",
    )
    parser.add_argument(
        "--asset-type",
        choices=("auto", "stock", "crypto"),
        default="auto",
    )
    parser.add_argument(
        "--analysts",
        default=",".join(ANALYSTS),
        help="comma-separated subset of market,social,news,fundamentals",
    )
    parser.add_argument("--provider", help="LLM provider override")
    parser.add_argument("--quick-model", help="quick-thinking model override")
    parser.add_argument("--deep-model", help="deep-thinking model override")
    parser.add_argument("--output-language", help="report language override")
    parser.add_argument("--debate-rounds", type=non_negative_int)
    parser.add_argument("--risk-rounds", type=non_negative_int)
    parser.add_argument(
        "--no-checkpoint",
        action="store_true",
        help="disable checkpoint/resume for this run",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate the checkout and Python environment without calling an LLM",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="show a traceback on failure",
    )
    args = parser.parse_args(argv)
    if not args.check and not args.ticker:
        parser.error("ticker is required unless --check is used")
    return args


def non_negative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be zero or greater")
    return parsed


def validate_date(value: str) -> str:
    try:
        parsed = date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError(f"invalid analysis date {value!r}; use YYYY-MM-DD") from exc
    if parsed > date.today():
        raise ValueError(f"analysis date {value!r} cannot be in the future")
    return value


def validate_ticker(value: str) -> str:
    ticker = value.strip().upper()
    if not TICKER_PATTERN.fullmatch(ticker):
        raise ValueError(
            "ticker must be 1-32 characters using letters, numbers, '.', '_', "
            "'-', '^', or '='"
        )
    return ticker


def parse_analysts(value: str, asset_type: str) -> tuple[str, ...]:
    analysts = tuple(part.strip().lower() for part in value.split(",") if part.strip())
    unknown = sorted(set(analysts) - set(ANALYSTS))
    if unknown:
        raise ValueError(f"unknown analysts: {', '.join(unknown)}")
    if asset_type == "crypto":
        analysts = tuple(analyst for analyst in analysts if analyst != "fundamentals")
    if not analysts:
        raise ValueError("at least one compatible analyst is required")
    return analysts


def add_repo_to_path(repo: Path) -> Path:
    resolved = repo.expanduser().resolve()
    required = resolved / "tradingagents" / "graph" / "trading_graph.py"
    if not required.is_file():
        raise ValueError(f"TradingAgents checkout not found at {resolved}")
    sys.path.insert(0, str(resolved))
    return resolved


def load_env_files(repo: Path) -> None:
    from dotenv import load_dotenv

    # Existing process variables retain highest precedence. Richbot's file is
    # loaded first, then missing TradingAgents-specific values come from its
    # own checkout.
    load_dotenv(BOT_ROOT / ".env", override=False)
    load_dotenv(repo / ".env", override=False)


def normalize_and_classify_ticker(ticker: str, requested_type: str) -> tuple[str, str]:
    from tradingagents.dataflows.symbol_utils import normalize_symbol

    normalized = normalize_symbol(validate_ticker(ticker))
    detected = "crypto" if normalized.endswith(CRYPTO_SUFFIXES) else "stock"
    return normalized, detected if requested_type == "auto" else requested_type


def create_config(args: argparse.Namespace, runtime_dir: Path) -> dict[str, Any]:
    from tradingagents.default_config import DEFAULT_CONFIG

    config = DEFAULT_CONFIG.copy()
    config.update(
        {
            "results_dir": str(runtime_dir / "logs"),
            "data_cache_dir": str(runtime_dir / "cache"),
            "memory_log_path": str(runtime_dir / "memory" / "trading_memory.md"),
            "checkpoint_enabled": not args.no_checkpoint,
        }
    )
    overrides = {
        "llm_provider": args.provider,
        "quick_think_llm": args.quick_model,
        "deep_think_llm": args.deep_model,
        "output_language": args.output_language,
        "max_debate_rounds": args.debate_rounds,
        "max_risk_discuss_rounds": args.risk_rounds,
    }
    config.update({key: value for key, value in overrides.items() if value is not None})
    return config


def append_decision(runtime_dir: Path, summary: dict[str, Any]) -> None:
    runtime_dir.mkdir(parents=True, exist_ok=True)
    with (runtime_dir / "decisions.jsonl").open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(summary, ensure_ascii=False) + "\n")


def run(args: argparse.Namespace) -> int:
    analysis_date = validate_date(args.date)
    repo = add_repo_to_path(args.repo)

    try:
        load_env_files(repo)
        from tradingagents.graph.trading_graph import TradingAgentsGraph
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            f"missing Python dependency {exc.name!r}; run "
            "scripts/setup_trading_agents.sh"
        ) from exc

    if args.check:
        print(f"TradingAgents checkout: {repo}")
        print("TradingAgents imports: ok")
        return 0

    ticker, asset_type = normalize_and_classify_ticker(args.ticker, args.asset_type)
    analysts = parse_analysts(args.analysts, asset_type)
    runtime_dir = args.runtime_dir.expanduser().resolve()
    reports_dir = args.reports_dir.expanduser().resolve()
    config = create_config(args, runtime_dir)

    graph = TradingAgentsGraph(
        selected_analysts=analysts,
        debug=False,
        config=config,
    )
    final_state, rating = graph.propagate(
        ticker,
        analysis_date,
        asset_type=asset_type,
    )

    generated_at = datetime.now(timezone.utc)
    stamp = generated_at.strftime("%Y%m%dT%H%M%SZ")
    safe_ticker = re.sub(r"[^A-Za-z0-9._-]", "_", ticker)
    report_dir = reports_dir / safe_ticker / f"{analysis_date}-{stamp}"
    report_path = graph.save_reports(final_state, ticker, report_dir)
    summary = {
        "ticker": ticker,
        "analysis_date": analysis_date,
        "generated_at": generated_at.isoformat(),
        "asset_type": asset_type,
        "analysts": list(analysts),
        "provider": config["llm_provider"],
        "quick_model": config["quick_think_llm"],
        "deep_model": config["deep_think_llm"],
        "rating": rating,
        "final_decision": final_state["final_trade_decision"],
        "report": str(report_path),
        "research_only": True,
    }
    (report_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    append_decision(runtime_dir, summary)

    print(f"TradingAgents {ticker} ({analysis_date}): {rating}")
    print(f"Report: {report_path}")
    print("Research only: no order or Richbot state was changed.")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        return run(args)
    except Exception as exc:
        if args.debug:
            raise
        print(f"TradingAgents failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
