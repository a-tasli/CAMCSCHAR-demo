import tempfile
import unittest
from pathlib import Path
import json

from tools.analyze_pilot_calibration import (
    StatisticalLoadConfig,
    load_records,
    replay_session,
    summarize_segment,
    sweep_bounded_parameters,
)


def make_tick(
    poi: str,
    hr: int,
    hrr_fraction: float,
    step_cv_pct: float,
    cadence: float = 115.0,
    is_walking: bool = True,
    is_speaking: bool = False,
    duty: float = 0.0,
) -> dict:
    step_time = 60000.0 / cadence if cadence > 0 else 500.0
    return {
        "timestamp": "2026-11-27T12:00:00.000+0300",
        "poi": poi,
        "hr": hr,
        "hrr_fraction": hrr_fraction,
        "physical_strain_score": min(100.0, max(0.0, hrr_fraction / 0.50 * 100.0)),
        "step_time_ms": step_time,
        "step_variance": (step_cv_pct / 100.0) * step_time,
        "step_cv_pct": step_cv_pct,
        "cadence_spm": cadence,
        "is_walking": is_walking,
        "is_speaking": is_speaking,
        "speech_duty_cycle_10s": duty,
        "mental_hr_bpm": 1.0,
        "trigger": "periodic",
    }


class PilotCalibrationTests(unittest.TestCase):
    def test_terrain_only_stairs_stay_low_while_distractors_escalate(self):
        cfg = StatisticalLoadConfig()

        # 60s baseline walk (CV ~ 1.5%) + 40s Segment 3 steep stairs silently (HRR=65%, CV=1.6%, duty=0)
        terrain_only = [make_tick("amphitheatre", 82, 0.15, 1.5) for _ in range(60)]
        terrain_only += [make_tick("tosun_terzioglu_garden", 148, 0.65, 1.6, cadence=95.0) for _ in range(40)]

        # Same baseline + Segment 3 stairs WITH Serial 3s aloud + gait disruption (CV=3.6%, duty=0.35)
        distractor = [make_tick("amphitheatre", 82, 0.15, 1.5) for _ in range(60)]
        distractor += [
            make_tick("tosun_terzioglu_garden", 152, 0.68, 3.6, cadence=92.0, is_speaking=True, duty=0.35)
            for _ in range(40)
        ]

        t_rep = replay_session(terrain_only, cfg)
        d_rep = replay_session(distractor, cfg)

        t_seg3 = summarize_segment([r for r in t_rep if r["poi"] == "tosun_terzioglu_garden"])
        d_seg3 = summarize_segment([r for r in d_rep if r["poi"] == "tosun_terzioglu_garden"])

        self.assertEqual(t_seg3["pct_high_plus"], 0.0)
        self.assertEqual(t_seg3["pct_med_plus"], 0.0)
        self.assertGreater(d_seg3["pct_high_plus"], 60.0)

        ranked = sweep_bounded_parameters([terrain_only], [distractor])
        self.assertTrue(len(ranked) > 0)
        best_score, _, best_stats = ranked[0]
        self.assertEqual(best_stats["terrain_seg3_high_pct"], 0.0)
        self.assertGreater(best_score, 0.0)

    def test_continuous_talking_alone_stays_medium_never_overload(self):
        cfg = StatisticalLoadConfig()
        calm_chat = [make_tick("amphitheatre", 78, 0.10, 1.5) for _ in range(60)]
        # 120 seconds of 100% non-stop talking while walking completely steadily at low HRR
        calm_chat += [make_tick("lake_memorial", 82, 0.15, 1.5, is_speaking=True, duty=1.0) for _ in range(120)]
        rep = replay_session(calm_chat, cfg)
        lake = summarize_segment([r for r in rep if r["poi"] == "lake_memorial"])
        self.assertEqual(lake["pct_overload"], 0.0)
        self.assertEqual(lake["pct_high_plus"], 0.0)
        self.assertGreater(lake["pct_med_plus"], 70.0)

    def test_load_unified_experiment_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "experiment_unified_P1.json"
            p.write_text(json.dumps({"participant_id": "P1", "experiment_data": [make_tick("amphitheatre", 75, 0.1, 1.4)]}))
            rows = load_records(p)
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]["poi"], "amphitheatre")


if __name__ == "__main__":
    unittest.main()
