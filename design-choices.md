# CAMCSCHAR — Statistical Pipeline Design Choices, Justifications, and Citations

**Date:** 2026-11-27  
**Companion Files:** `pipeline-spec.md`, `scenario.md`, `calibration-strategy.md`, `app/src/main/kotlin/org/example/StatisticalLoadModel.kt`, `app/src/main/kotlin/org/example/GaitAnalyzer.kt`, `tools/analyze_pilot_calibration.py`  

This document records the engineering and methodological rationale for every modeling, sensing, and calibration decision replacing hand-tuned population heuristics prior to the frozen `N = 30` field study.

---

## 1. Per-Participant Step-Time CV Normalization (Instead of Raw ms Thresholds)

### Decision
- Replace `(avgStepTime − 500) / 200` and `(stepTimeVar − 20) / 50` in [GaitAnalyzer.kt](app/src/main/kotlin/org/example/GaitAnalyzer.kt) with **Step-Time Coefficient of Variation** (`stepCvPct = stepTimeVariability / stepTime * 100`), computed over relative-median inliers (`0.65×median .. 1.45×median`) and standardized against a frozen personal calm-walking baseline (`zGait = clamp((stepCvPct − baseline.stepCvPct) / baseline.sigmaStepCvPct, 0, 3.0)`).
- Enforce healthy young-adult warm-start norms (`priorStepTimeMs = 500.0`, `priorStepCvPct = 1.5%`, `priorSigmaStepCvPct = 0.5%`) and a minimum dispersion floor (`sigmaStepCvFloor = 0.40%`), computed using outlier-robust **Median + scaled MAD (`1.4826 × MAD`)**.
- Use a **`4.0 s` walking timeout** (`stopTimeoutSec = 4.0`) paired with a **`1.5 s` step-freshness gate** (`stepFreshnessSec = 1.5`): once `> 1.5 s` elapses without a new step, `zGait` becomes **neutral (`0.0`)** immediately while awaiting the `4.0 s` stop timeout, and restarting after `>= 4.0 s` pause clears stale steps from the prior bout.

### Justification & Citations
1. **Unscaled `ms` bias & normative CV:** Preferred single-task walking cadence in healthy young adults centers near `~500 ms` per step (`~1,000 ms` stride), with single-task stride/step time CV normally between **1.0% and 3.0%** (typically **~1.5% ± 0.5%**), and values above **3–4% CV** marking distraction, instability, or artifact. Setting `20 ms SD` as "0 load" (`4% CV` at 500 ms) was above single-task young-adult norms while simultaneously misclassifying naturally slower/taller walkers (`avgStepTime = 600 ms`) as cognitively loaded.
2. **Task prioritization in healthy adults:** Dual-task walking meta-analyses show healthy young adults tightly regulate rhythmic stepping under light loads and primarily exhibit elevated timing variability under complex executive dual tasks (e.g., aloud serial subtraction) or challenging irregular terrain. Relative within-subject z-scoring captures changes *from each individual's own motor baseline* rather than penalizing anthropometric stride differences.
3. **Robust dispersion (`Median + MAD`) & variance floor:** Pre-walk setup anxiety or an occasional stutter step can inflate sample SD; Median + MAD ignores transient outliers, and `sigmaStepCvFloor = 0.40%` prevents hyper-steady walkers (`MAD → 0`) from triggering infinite z-scores on minor steps.

---

## 2. Manual Pre-Tour Baseline Gate (`CalibrationScreen.kt`)

### Decision
- Gate access to [TourScreen.kt](app/src/main/kotlin/org/example/TourScreen.kt) (`"Start Tour"`) behind an explicit pre-tour calibration screen ([CalibrationScreen.kt](app/src/main/kotlin/org/example/CalibrationScreen.kt)) requiring the user/researcher to press **"Begin Calibration (60 s Walk)"** and complete `60` valid calm silent walking seconds (`isWalking && !isSpeaking`) before locking baselines (`LOCKED`).
- Reset all baseline, resting HR, and CUSUM states cleanly on survey completion (`analyzer.resetSession()`), with a researcher-only `Reset Calibration` / `Bypass (Use Norms)` override inside [DebugScreen.kt](app/src/main/kotlin/org/example/DebugScreen.kt).

### Justification
- Avoids silently absorbing late-walk fatigue or Segment 3 distractors into an adaptive baseline, keeping Segment 1–3 contrasts completely non-circular relative to a frozen personal walking baseline established prior to active POIs ([scenario.md §5–§6](scenario.md)).

---

## 3. Headphone-Only Continuous Silero VAD & Duty-Cycle Scoring (Removal of Speaker Mode)

### Decision
- Remove all loudspeaker/microphone self-suppression (`VoiceSensorManager.setTtsActive()`, `TTSManager.isSpeakingOut`) and volume-ducking dead code (`AudioTourParams.duckWhileSpeaking`, `TTSManager.setDucking`).
- Replace the unconditional instant `+1` tier bump on binary `isSpeaking` with a bounded standardized score on **10-second rolling Silero VAD duty cycle (`speechDutyCycle10s`)**: `zVoice = clamp((vocalDuty − 0.10) / 0.25, 0, 2.5)`.

### Justification
1. **Experimental fidelity with wired headsets:** All field sessions and pilot runs use wired headphones/earphones ([scenario.md §2.3](scenario.md#L82)); participants respond to Segment 2 prompts and recite Segment 3 Serial 3s *concurrently during narration playback*. Suppressing Silero VAD whenever `TTSManager` plays audio would blind the vocal load sensor throughout active tour delivery.
2. **Rejection of transient non-task noise:** Brief acoustic events (`< 10%` of a 10 s window, e.g., a cough or `< 1 s` throat clear) stay inside the `0.10` deadzone (`zVoice = 0.0`), whereas sustained conversational replies and paced Serial 3s utterances (`25–45%+` duty) scale proportionally into `1.0–2.3σ`.

---

## 4. ACSM Moderate-to-Vigorous `%HRR` Capacity-Reserve Hinge (`40% → 60% HRR`)

### Decision
- Ground cardiovascular physical workload via Karvonen **Heart Rate Reserve (`%HRR`)** using **Tanaka et al. (2001)** age-predicted maximum HR (`HRmax = 208 − 0.7 × age`, avoiding uncalibrated `220 − age` assumptions).
- Apply a **piecewise deadzone hinge** instead of a global linear weight:
  - **`%HRR ≤ 0.40`:** `strainReserveBonus = 0.0`.
  - **`0.40 < %HRR < 0.60`:** ramps linearly from `0.0` to `maxStrainReserveBonus = 1.50`.
  - **`%HRR ≥ 0.60`:** capped strictly at `1.50`.
- Combine with equal unit-weighted cognitive departure channels (`zGait = 1.0×`, `zVoice = 1.0×`), enforcing **`maxStrainReserveBonus (1.50) < threshMedium (2.0)`** and **`< 0.5 × threshHigh (4.0)`**.

### Justification & Citations
1. **ACSM Exercise-Intensity Zones:** American College of Sports Medicine (ACSM) guidelines classify `< 30% HRR` as Very Light, `30–39% HRR` as Light, **`40–59% HRR` as Moderate**, and **`60–89% HRR` as Vigorous**.
2. **Exercise-Cognition Literature (Lambourne & Tomporowski, 2010; Inverted-U / Ventilatory Threshold):** Acute exercise-cognition meta-regression demonstrates that light-to-lower-moderate locomotion does not degrade concurrent cognitive task accuracy (and can maintain optimal arousal), whereas encroaching on vigorous aerobic intensities past ventilatory/metabolic thresholds triggers central attentional resource competition and transient hypofrontality.
3. **Structural Invariant Against False Stair Alarms:** Keeping `maxStrainReserveBonus < threshMedium` guarantees mathematically that climbing Segment 3 stairs *silently with calm gait* (`zCog = 0`) never falsely pushes adaptation into `Medium`, `High`, or `Overload` — while concurrent stairs (`+1.5σ` reserve strain) plus cognitive-motor or speech departure reaches `High`/`Overload` faster than flat walking alone.
4. **Equal Unit Weighting (No Outcome-Tuned Weights):** In the absence of an `N ≥ 20` labeled pre-study training cohort, assigning equal unit weights (`1.0`) to direct standardized cognitive channels avoids arbitrary subjective feature tuning and prevents overfitting.

---

## 5. Exploratory `mental_hr_bpm` (Logged Only) & Stop-Only Recovery Fix

### Decision
- Demote cadence-residual `mental_hr_bpm` (Michael Myrtek's **Additional Heart Rate / AHR** estimate) to **logged diagnostic telemetry only** (`ExperimentDataManager`), excluded from driving live CUSUM decisions.
- Fix its recovery-gating logic inside [GaitAnalyzer.kt](app/src/main/kotlin/org/example/GaitAnalyzer.kt):
  1. Remove continuous walking resets on `|Δcadence| > 15 steps/min` (preventing spurious 60 s muting on cobblestones/stairs).
  2. Replace symmetric 45 s prediction lag with **asymmetric cardiac kinetics**: `τ_onset = 20.0 s` (when `hrTarget > hrPredicted`) and `τ_recovery = 40.0 s` (when `hrTarget < hrPredicted`).
  3. Trigger the compacted **`20 s` recovery mute (`10 s` ramp-in)** *only* on walking-to-stopped transitions (`lastIsWalking && !isWalking`) and hold muted while stationary.

### Justification & Citations
1. **Scientific basis & wearable caveats:** Additional Heart Rate (Myrtek, Freiburg Monitoring System) separates non-metabolic psychological cardiac reactivity from metabolic exercise HR (`~3–8 bpm` mental shifts; Makowiec-Dąbrowska et al., 1992; wearable motion PPG limits in Mach et al., 2022 and forearm-PPG RMSSD walking noise floor in `pipeline-spec.md §5`).
2. **Documented incline/cadence limitation:** AHR literature shows flat-calibrated accelerometer/cadence regressions fail on **inclines and stairs**: uphill locomotion elevates aerobic VO₂ and HR dramatically while stride cadence slows or stays constant, creating false-positive AHR residuals blind to terrain grade. Because Segment 3 ([scenario.md](scenario.md#L161)) traverses uneven stairs without barometer/grade sensing, excluding `mental_hr_bpm` from live triggering protects `High`/`Overload` validity while logging it for post-hoc analysis on flat Segment 2.
3. **Autonomic kinetics asymmetry:** Fast HR onset upon pace increases is mediated within **~10–20 s** via parasympathetic (vagal) withdrawal, whereas HR decay after stopping relies on slower **30–60 s** parasympathetic reactivation. Asymmetric predictor kinetics absorb routine walking pace variations cleanly, isolating recovery muting strictly to abrupt walking stops.

---

## 6. Bounded Stationary EWMA, Single-Channel Ceilings & Dwell Hysteresis

### Decision
- Bound cognitive channel contributions strictly below `High (4.0)`: `zGait <= 3.0`, `zVoice <= 2.5`, with `zCog = zGait + zVoice`.
- Feed `zCog` into a stationary first-order **EWMA control filter** (`ewmaTauSec = 6.0 s`, `alpha = 1 − e^(−dt/6)`), adding `strainReserveBonus` (`<= 1.50`) afterwards (`effectiveEvidence = E_t + strainReserveBonus`).
- Map thresholds `2.0 / 4.0 / 5.5` (`×25 → 50 / 100 / 137.5` `combined_load_index`) with **`minDownDwellTicks = 5 s`** downward hysteresis.
- Audit bounded anti-chatter constants offline across the `2 × 5` researcher pilot ablation (`2 Terrain-Only` vs. `3 Terrain + Distractors`) via `tools/analyze_pilot_calibration.py`, then freeze all parameters identically across the `N = 30` randomized trial.

### Justification
- **Why stationary EWMA instead of integrating CUSUM:** Pure one-sided CUSUM integrates *time × signal* (`S_{t-1} + α(z − k)`), causing any sustained moderate conversation (`zVoice > k`) to wind up monotonically to `Overload` purely from duration rather than severity. An **EWMA control statistic** (Lucas & Saccucci, 1990) still rejects brief 1-second glitches (`τ = 6.0 s`) while converging cleanly to true steady-state intensity (`E_∞ = zCog`), so calm prolonged speaking remains at `Medium` rather than spiraling into `Overload`.
- **Single-channel Overload immunity:** Capping `zGait <= 3.0`, `zVoice <= 2.5`, and `strainReserveBonus <= 1.5` enforces mathematically that *neither* walking irregularity alone, *nor* continuous speaking alone, *nor* uphill exertion alone can trigger `High` or `Overload` — requiring genuine multi-channel overlap ([scenario.md §3](scenario.md#L165)).
- **Immunity to stopping braking tails & missed steps:** Relative-median step filtering (`[0.65×med, 1.45×med]`) plus the `1.5 s` freshness gate eliminate abrupt variance spikes from final braking steps during stops.
- **No `n = 2` supervised leakage:** Offline pilot runs are strictly used to verify Terrain-Only vs. Distractor separation on stairs and bound chatter rate (`switches/min`), **not** to fit supervised classifiers on Segment 1–3 labels ([calibration-strategy.md](calibration-strategy.md)).
