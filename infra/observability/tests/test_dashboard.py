"""Offline contracts for the dashboard; no Grafana or cloud access required."""

import json
import unittest
from pathlib import Path


class DashboardContractsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dashboard = json.loads((Path(__file__).resolve().parents[1] / "dashboard/tcg-store-backend.json").read_text(encoding="utf-8"))

    def test_environments_remain_selectable_when_all_scrapes_are_missing(self):
        environment = next(value for value in self.dashboard["templating"]["list"] if value["name"] == "environment")
        self.assertEqual("custom", environment["type"])
        self.assertEqual({"staging", "production"}, {value["value"] for value in environment["options"]})

    def test_every_panel_has_an_explicit_unknown_no_value(self):
        for panel in self.dashboard["panels"]:
            self.assertEqual("UNKNOWN", panel["fieldConfig"]["defaults"]["noValue"], panel["title"])

    def test_panels_do_not_overlap_and_fit_the_dashboard_grid(self):
        rectangles = []
        identifiers = set()
        for panel in self.dashboard["panels"]:
            self.assertNotIn(panel["id"], identifiers)
            identifiers.add(panel["id"])
            grid = panel["gridPos"]
            left, top, width, height = (grid[key] for key in ("x", "y", "w", "h"))
            self.assertTrue(left >= 0 and top >= 0 and width > 0 and height > 0 and left + width <= 24)
            for other_id, x, y, w, h in rectangles:
                overlaps = left < x + w and x < left + width and top < y + h and y < top + height
                self.assertFalse(overlaps, f"panels {panel['id']} and {other_id} overlap")
            rectangles.append((panel["id"], left, top, width, height))

    def test_expected_signal_families_are_present(self):
        expressions = " ".join(target["expr"] for panel in self.dashboard["panels"] for target in panel["targets"])
        for signal in ("up{", "http_requests", "http_5xx", "p95_latency", "jvm_memory", "hikaricp_", "tcg:s3:", "tcg:neon:", "restarts_total", "OOMKilled"):
            self.assertIn(signal, expressions)


if __name__ == "__main__":
    unittest.main()
