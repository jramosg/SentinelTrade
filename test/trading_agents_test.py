import argparse
import json
import sys
import types
import unittest
from datetime import date
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from scripts import trading_agents as runner


class FakeGraph:
    def __init__(self, selected_analysts, debug, config):
        self.selected_analysts = selected_analysts
        self.config = config

    def propagate(self, ticker, analysis_date, asset_type):
        state = {"final_trade_decision": "**Rating**: Hold"}
        return state, "Hold"

    def save_reports(self, final_state, ticker, report_dir):
        report_dir.mkdir(parents=True)
        report = report_dir / "complete_report.md"
        report.write_text(f"# {ticker}\n", encoding="utf-8")
        return report


def fake_modules():
    tradingagents = types.ModuleType("tradingagents")
    tradingagents.__path__ = []
    graph = types.ModuleType("tradingagents.graph")
    graph.__path__ = []
    trading_graph = types.ModuleType("tradingagents.graph.trading_graph")
    trading_graph.TradingAgentsGraph = FakeGraph
    dataflows = types.ModuleType("tradingagents.dataflows")
    dataflows.__path__ = []
    symbols = types.ModuleType("tradingagents.dataflows.symbol_utils")
    symbols.normalize_symbol = lambda ticker: ticker
    defaults = types.ModuleType("tradingagents.default_config")
    defaults.DEFAULT_CONFIG = {
        "llm_provider": "test-provider",
        "quick_think_llm": "quick",
        "deep_think_llm": "deep",
    }
    return {
        "tradingagents": tradingagents,
        "tradingagents.graph": graph,
        "tradingagents.graph.trading_graph": trading_graph,
        "tradingagents.dataflows": dataflows,
        "tradingagents.dataflows.symbol_utils": symbols,
        "tradingagents.default_config": defaults,
    }


class TradingAgentsRunnerTest(unittest.TestCase):
    def test_crypto_omits_fundamentals(self):
        analysts = runner.parse_analysts(
            "market,news,fundamentals",
            "crypto",
        )
        self.assertEqual(("market", "news"), analysts)

    def test_ticker_validation_blocks_paths(self):
        with self.assertRaises(ValueError):
            runner.validate_ticker("../../secret")

    def test_research_run_writes_report_summary_and_history(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = argparse.Namespace(
                ticker="COST",
                date=str(date.today()),
                repo=root / "TradingAgents",
                runtime_dir=root / "runtime",
                reports_dir=root / "reports",
                asset_type="auto",
                analysts="market,news,fundamentals",
                provider=None,
                quick_model=None,
                deep_model=None,
                output_language=None,
                debate_rounds=None,
                risk_rounds=None,
                no_checkpoint=False,
                check=False,
                debug=False,
            )
            with (
                patch.dict(sys.modules, fake_modules()),
                patch.object(runner, "add_repo_to_path", return_value=args.repo),
                patch.object(runner, "load_env_files"),
            ):
                result = runner.run(args)

            self.assertEqual(0, result)
            summaries = list(args.reports_dir.rglob("summary.json"))
            self.assertEqual(1, len(summaries))
            summary = json.loads(summaries[0].read_text(encoding="utf-8"))
            self.assertEqual("Hold", summary["rating"])
            self.assertTrue(summary["research_only"])
            history = (args.runtime_dir / "decisions.jsonl").read_text(
                encoding="utf-8"
            )
            self.assertEqual("Hold", json.loads(history)["rating"])


if __name__ == "__main__":
    unittest.main()
