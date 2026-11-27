#!/usr/bin/env python3
"""
Offline Pilot Replay & Bounded Calibration Tool for CAMCSCHAR (2x5 Researcher Runs).

Reads exported experiment_*.json or experiment_unified_*.json logs split into:
  --terrain-only : quiet campus walks without distractors (isolates hills/stairs)
  --distractors  : full protocol walks with spoken prompts & Serial 3s math

Implements exact parity with StatisticalLoadModel.kt (Median/MAD baseline calibration,
ACSM 40-60% HRR capacity hinge, sustained VAD duty z-score, leaky one-sided CUSUM,
and downward dwell hysteresis) so bounded anti-chatter/reserve constants can be
audited without fitting supervised classifiers on n=2 researchers.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import math
from pathlib import Path
from typing import Any


@dataclasses.dataclass(frozen=True)
class StatisticalLoadConfig:
    prior_step_time_ms: float = 500.0
    prior_step_cv_pct: float = 1.5
    prior_sigma_step_cv_pct: float = 0.5
    sigma_step_cv_floor: float = 0.40
    baseline_target_ticks: int = 60
    voice_duty_deadzone: float = 0.10
    voice_duty_scale: float = 0.25
    z_gait_max: float = 3.0
    z_voice_max: float = 2.5
    hrr_deadzone: float = 0.40
    hrr_full_scale: float = 0.60
    max_strain_reserve_bonus: float = 1.50
    ewma_tau_sec: float = 6.0
    thresh_medium: float = 2.0
    thresh_high: float = 4.0
    thresh_overload: float = 5.5
    min_down_dwell_ticks: int = 5


def median(values: list[float]) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    mid = len(s) // 2
    if len(s) % 2 == 0:
        return (s[mid - 1] + s[mid]) / 2.0
    return s[mid]


def scaled_mad(values: list[float], med: float | None = None) -> float:
    if not values:
        return 0.0
    m = median(values) if med is None else med
    return 1.4826 * median([abs(v - m) for v in values])


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    idx = (len(s) - 1) * (pct / 100.0)
    lo = math.floor(idx)
    hi = math.ceil(idx)
    if lo == hi:
        return s[lo]
    return s[lo] + (s[hi] - s[lo]) * (idx - lo)


def load_records(path: Path) -> list[dict[str, Any]]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(raw, list):
        rows = raw
    elif isinstance(raw, dict):
        rows = raw.get("experiment_data") or raw.get("sensor_data") or []
    else:
        raise ValueError(f"Unsupported JSON root in {path}")
    # Filter duplicate event rows close in time if needed, keep ordered periodic+event stream
    return [r for r in rows if isinstance(r, dict)]


def infer_hrr_fraction(row: dict[str, Any]) -> float | None:
    if row.get("hrr_fraction") is not None:
        return float(row["hrr_fraction"])
    strain = row.get("physical_strain_score")
    if row.get("hr") is None or strain is None:
        return None
    return float(strain) * 0.50 / 100.0


def infer_step_cv_pct(row: dict[str, Any]) -> float | None:
    if row.get("step_cv_pct") is not None and float(row["step_cv_pct"]) > 0:
        return float(row["step_cv_pct"])
    step_time = row.get("step_time_ms")
    step_var = row.get("step_variance")
    if step_time and step_var is not None and float(step_time) > 0:
        return (float(step_var) / float(step_time)) * 100.0
    return None


def replay_session(
    rows: list[dict[str, Any]],
    cfg: StatisticalLoadConfig = StatisticalLoadConfig(),
    auto_calibrate_first_walking: bool = True,
) -> list[dict[str, Any]]:
    """Replay statistical baseline + stationary EWMA controller cleanly over a single run."""
    step_cv_samples: list[float] = []
    locked_cv0 = cfg.prior_step_cv_pct
    locked_sigma_cv = cfg.prior_sigma_step_cv_pct
    calibrated = not auto_calibrate_first_walking

    ewma = 0.0
    current_tier = 0
    down_dwell = 0
    initialized = False
    out_rows: list[dict[str, Any]] = []

    tier_names = {0: "Low", 1: "Medium", 2: "High", 3: "Overload"}

    for row in rows:
        is_walking = bool(row.get("is_walking", (row.get("cadence_spm") or 0.0) > 0 or (row.get("step_variance") or 0.0) > 0))
        is_speaking = bool(row.get("is_speaking", False))
        step_cv = infer_step_cv_pct(row) if is_walking else None
        hrr = infer_hrr_fraction(row)
        duty = row.get("speech_duty_cycle_10s")

        # Simulate calm-walking calibration if replaying uncalibrated stream
        if not calibrated and is_walking and not is_speaking and step_cv is not None:
            step_cv_samples.append(step_cv)
            if len(step_cv_samples) >= cfg.baseline_target_ticks:
                locked_cv0 = median(step_cv_samples)
                locked_sigma_cv = max(scaled_mad(step_cv_samples, locked_cv0), cfg.sigma_step_cv_floor)
                calibrated = True

        # Respect explicit pre-locked baseline field if present when starting playback
        if not calibrated and row.get("baseline_calibrated") and row.get("baseline_step_cv_pct"):
            locked_cv0 = float(row["baseline_step_cv_pct"])
            calibrated = True

        has_signal = (row.get("hr") is not None) or (step_cv is not None) or is_speaking or ((duty or 0.0) > 0.10)
        if not has_signal and not initialized:
            out_rows.append({**row, "replay_level": "Waiting...", "replay_cusum": 0.0, "replay_index": None})
            continue
        initialized = True

        z_gait = min(cfg.z_gait_max, max(0.0, (step_cv - locked_cv0) / locked_sigma_cv)) if (is_walking and step_cv is not None) else 0.0
        vocal_duty = float(duty) if duty is not None else (0.50 if is_speaking else 0.0)
        vocal_duty = min(1.0, max(0.0, vocal_duty))
        z_voice = min(cfg.z_voice_max, max(0.0, (vocal_duty - cfg.voice_duty_deadzone) / cfg.voice_duty_scale))

        if hrr is not None and math.isfinite(hrr):
            over = min(1.0, max(0.0, (hrr - cfg.hrr_deadzone) / (cfg.hrr_full_scale - cfg.hrr_deadzone)))
        else:
            over = 0.0
        reserve = over * cfg.max_strain_reserve_bonus

        z_cog = z_gait + z_voice
        alpha = 1.0 - math.exp(-1.0 / max(cfg.ewma_tau_sec, 0.5))
        ewma += alpha * (z_cog - ewma)

        eff = ewma + reserve
        if eff >= cfg.thresh_overload:
            raw_tier = 3
        elif eff >= cfg.thresh_high:
            raw_tier = 2
        elif eff >= cfg.thresh_medium:
            raw_tier = 1
        else:
            raw_tier = 0

        if raw_tier > current_tier:
            current_tier = raw_tier
            down_dwell = 0
        elif raw_tier < current_tier:
            down_dwell += 1
            if down_dwell >= cfg.min_down_dwell_ticks:
                current_tier = raw_tier
                down_dwell = 0
        else:
            down_dwell = 0

        out_rows.append({
            **row,
            "replay_z_gait": z_gait,
            "replay_z_voice": z_voice,
            "replay_reserve": reserve,
            "replay_cusum": ewma,
            "replay_index": eff * 25.0,
            "replay_level": tier_names[current_tier],
        })

    return out_rows


def summarize_segment(rows: list[dict[str, Any]]) -> dict[str, Any]:
    n = len(rows)
    if n == 0:
        return {"ticks": 0}
    periodic_rows = [r for r in rows if r.get("trigger", "periodic") == "periodic"] or rows
    m = len(periodic_rows)

    levels = [r.get("replay_level", r.get("load_level", "Waiting...")) for r in periodic_rows]
    switches = sum(1 for i in range(1, len(levels)) if levels[i] != levels[i - 1])
    duration_min = max(m / 60.0, 1e-6)

    cvs = [float(infer_step_cv_pct(r)) for r in periodic_rows if infer_step_cv_pct(r) is not None]
    hrrs = [float(infer_hrr_fraction(r)) * 100.0 for r in periodic_rows if infer_hrr_fraction(r) is not None]
    cusums = [float(r.get("replay_cusum", r.get("cusum_score") or 0.0)) for r in periodic_rows]
    mentals = [float(r["mental_hr_bpm"]) for r in periodic_rows if r.get("mental_hr_bpm") is not None]
    duties = [float(r["speech_duty_cycle_10s"]) * 100.0 for r in periodic_rows if r.get("speech_duty_cycle_10s") is not None]

    return {
        "ticks": m,
        "pct_med_plus": 100.0 * sum(1 for lv in levels if lv in ("Medium", "High", "Overload")) / m,
        "pct_high_plus": 100.0 * sum(1 for lv in levels if lv in ("High", "Overload")) / m,
        "pct_overload": 100.0 * sum(1 for lv in levels if lv == "Overload") / m,
        "switches_per_min": switches / duration_min,
        "mean_cv": sum(cvs) / len(cvs) if cvs else 0.0,
        "p90_cv": percentile(cvs, 90.0) if cvs else 0.0,
        "mean_hrr_pct": sum(hrrs) / len(hrrs) if hrrs else 0.0,
        "p90_hrr_pct": percentile(hrrs, 90.0) if hrrs else 0.0,
        "mean_cusum": sum(cusums) / len(cusums) if cusums else 0.0,
        "mean_mental_hr": sum(mentals) / len(mentals) if mentals else 0.0,
        "mean_vad_duty_pct": sum(duties) / len(duties) if duties else 0.0,
    }


def group_by_poi(rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    groups: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        poi = str(r.get("poi") or "None")
        groups.setdefault(poi, []).append(r)
    return groups


def estimate_walking_hr_coefficient(rows: list[dict[str, Any]]) -> float | None:
    """Empirical HR-vs-cadence ratio sanity check on calm walking samples."""
    hrs = [float(r["hr"]) for r in rows if r.get("hr") is not None]
    if not hrs:
        return None
    resting_est = percentile(hrs, 5.0)
    slopes = []
    for r in rows:
        hr = r.get("hr")
        cad = r.get("cadence_spm")
        speaking = r.get("is_speaking", False)
        if hr is not None and cad and float(cad) > 40.0 and not speaking:
            slopes.append((float(hr) - resting_est) / float(cad))
    return median(slopes) if slopes else None


def sweep_bounded_parameters(
    terrain_runs: list[list[dict[str, Any]]],
    distractor_runs: list[list[dict[str, Any]]],
) -> list[tuple[float, StatisticalLoadConfig, dict[str, float]]]:
    """
    Sweep ONLY physically grounded parameter bounds (NOT segment classifiers)
    to audit stair-only false-positive suppression vs distractor sensitivity & chatter.
    """
    candidates: list[tuple[float, StatisticalLoadConfig, dict[str, float]]] = []
    for reserve_cap in (0.0, 1.0, 1.5):
        for tau in (4.0, 6.0, 9.0):
            for voice_scale in (0.20, 0.25, 0.30):
                for dwell in (3, 5, 8):
                    cfg = StatisticalLoadConfig(
                        max_strain_reserve_bonus=reserve_cap,
                        ewma_tau_sec=tau,
                        voice_duty_scale=voice_scale,
                        min_down_dwell_ticks=dwell,
                    )
                    t_seg3_high, d_seg2_med, d_seg3_high, chatters = [], [], [], []
                    for rows in terrain_runs:
                        rep = replay_session(rows, cfg)
                        by_poi = group_by_poi(rep)
                        s3 = summarize_segment(by_poi.get("tosun_terzioglu_garden", []))
                        if s3["ticks"] > 0:
                            t_seg3_high.append(s3["pct_high_plus"])
                            chatters.append(s3["switches_per_min"])
                    for rows in distractor_runs:
                        rep = replay_session(rows, cfg)
                        by_poi = group_by_poi(rep)
                        s2 = summarize_segment(by_poi.get("lake_memorial", []))
                        s3 = summarize_segment(by_poi.get("tosun_terzioglu_garden", []))
                        if s2["ticks"] > 0:
                            d_seg2_med.append(s2["pct_med_plus"])
                            chatters.append(s2["switches_per_min"])
                        if s3["ticks"] > 0:
                            d_seg3_high.append(s3["pct_high_plus"])
                            chatters.append(s3["switches_per_min"])

                    mean_t3_fp = sum(t_seg3_high) / len(t_seg3_high) if t_seg3_high else 0.0
                    mean_d2 = sum(d_seg2_med) / len(d_seg2_med) if d_seg2_med else 0.0
                    mean_d3 = sum(d_seg3_high) / len(d_seg3_high) if d_seg3_high else 0.0
                    mean_chatter = sum(chatters) / len(chatters) if chatters else 0.0

                    # Reward strong distractor sensitivity + low chatter + zero terrain-only false High+
                    score = (mean_d3 - 2.0 * mean_t3_fp) + 0.3 * mean_d2 - 3.0 * mean_chatter
                    candidates.append((
                        score,
                        cfg,
                        {
                            "terrain_seg3_high_pct": mean_t3_fp,
                            "distract_seg2_med_pct": mean_d2,
                            "distract_seg3_high_pct": mean_d3,
                            "mean_chatter_per_min": mean_chatter,
                        },
                    ))
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates


def print_cohort_report(label: str, files: list[Path], cfg: StatisticalLoadConfig) -> list[list[dict[str, Any]]]:
    runs: list[list[dict[str, Any]]] = []
    poi_buckets: dict[str, list[dict[str, Any]]] = {}
    slopes: list[float] = []

    for f in files:
        rows = load_records(f)
        runs.append(rows)
        slope = estimate_walking_hr_coefficient(rows)
        if slope is not None:
            slopes.append(slope)
        rep = replay_session(rows, cfg)
        for poi, seg_rows in group_by_poi(rep).items():
            poi_buckets.setdefault(poi, []).extend(seg_rows)

    print(f"\n=== {label} ({len(files)} runs) ===")
    if slopes:
        print(f"Estimated steady-state HR/cadence slope (median across runs): {median(slopes):.3f} bpm/(step/min)")
    for poi in ("amphitheatre", "lake_memorial", "tosun_terzioglu_garden"):
        s = summarize_segment(poi_buckets.get(poi, []))
        if s["ticks"] == 0:
            continue
        print(
            f"  {poi:24s} | n={s['ticks']:4d}s | CV={s['mean_cv']:.2f}% (p90 {s['p90_cv']:.2f}%) "
            f"| HRR={s['mean_hrr_pct']:.1f}% (p90 {s['p90_hrr_pct']:.1f}%) "
            f"| VAD={s['mean_vad_duty_pct']:.1f}% | Med+={s['pct_med_plus']:.1f}% "
            f"High+={s['pct_high_plus']:.1f}% Ovl={s['pct_overload']:.1f}% "
            f"| chatter={s['switches_per_min']:.2f}/min"
        )
    return runs


def main() -> None:
    parser = argparse.ArgumentParser(description="CAMCSCHAR 2x5 pilot replay & bounded calibration audit")
    parser.add_argument("--terrain-only", nargs="*", type=Path, default=[], help="Terrain-only JSON log paths")
    parser.add_argument("--distractors", nargs="*", type=Path, default=[], help="Terrain+Distractors JSON log paths")
    parser.add_argument("--sweep", action="store_true", help="Run bounded CUSUM/reserve sensitivity check")
    args = parser.parse_args()

    cfg = StatisticalLoadConfig()
    t_runs = print_cohort_report("Terrain-Only Pilot Runs", args.terrain_only, cfg) if args.terrain_only else []
    d_runs = print_cohort_report("Terrain + Distractors Pilot Runs", args.distractors, cfg) if args.distractors else []

    if args.sweep and (t_runs or d_runs):
        print("\n=== Top 5 Bounded Parameter Candidates (No Classifier Fitting) ===")
        for rank, (score, c, stats) in enumerate(sweep_bounded_parameters(t_runs, d_runs)[:5], start=1):
            print(
                f"  #{rank} score={score:6.2f} | cap={c.max_strain_reserve_bonus:.1f} tau={c.ewma_tau_sec:.1f}s "
                f"voiceScale={c.voice_duty_scale:.2f} dwell={c.min_down_dwell_ticks}s "
                f"-> T3_High+={stats['terrain_seg3_high_pct']:.1f}% "
                f"D2_Med+={stats['distract_seg2_med_pct']:.1f}% "
                f"D3_High+={stats['distract_seg3_high_pct']:.1f}% "
                f"chatter={stats['mean_chatter_per_min']:.2f}/min"
            )


if __name__ == "__main__":
    main()
