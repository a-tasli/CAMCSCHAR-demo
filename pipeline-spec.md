# CAMCSCHAR Demo — Real-Time Cognitive Load Pipeline Specification

**Status:** Implemented (cardiac channel redesign)
**Last updated:** 2026-11-02
**Scope:** Android app (`app/src/main/kotlin/org/example/`) and data schema consumed by `visualizer/`.

This document describes the complete real-time pipeline: how raw sensor streams are
acquired, filtered, scored, fused into a combined cognitive-load index and level, how the
level drives the adaptive tour, and how everything is logged for the visualizer.

---

## 1. Overview

The system estimates a participant's cognitive load in real time while they walk a
campus tour, and adapts the audio tour (speech rate, pacing, detail level, image size,
audio ducking) to that estimate. Load is computed every **1 second** from three scored
channels — **gait**, **cardiac (HR)**, and a **voice-activity modifier** — plus passive
streams (GPS, audio) used for logging and presentation.

```
 Decathlon BLE armband          Phone step detector        Phone microphone
 (BLE Heart Rate Service)       (TYPE_STEP_DETECTOR)       (AudioRecord 8 kHz)
        │                              │                          │
        ▼                              ▼                          ▼
 BleHeartRateManager            GaitSensorManager          VoiceSensorManager
  • HR (bpm)                     • step timestamps (ns)     • isSpeaking (debounced)
  • RR intervals (1/1024 s)      │                          │
    → converted to ms            │                          │
    → artifact filter            │                          │
        │                        ▼                          │
        └──────────────►  GaitAnalyzer.analyze(hr, isSpeaking)   ◄───────┘
                              called every 1 s (MainActivity loop)
                                   │
                     ┌─────────────┼──────────────────┐
                     ▼             ▼                  ▼
               Gait channel  Cardiac channel   Voice modifier
              (gaitLoadIndex) (hrLoadIndex)     (level bump)
                     └─────────────┼──────────────────┘
                                   ▼
                    combinedLoadIndex → loadLevel
                    (Low / Medium / High / Overload)
                                   │
              ┌────────────────────┼─────────────────────┐
              ▼                    ▼                     ▼
       TourManager           ExperimentDataManager   Visualizer (web)
       (adaptive tour)       (JSON logging)          (synced charts)
```

Key change vs. the previous design: **HRV (SDNN/RMSSD) is no longer used for scoring.**
Forearm-PPG RMSSD from the armband sits at the sensor noise floor (~3–10 ms) while
moving, so the HRV terms were replaced by a **physical-strain score** (Karvonen %HRR)
plus a **motion-corrected mental-HR residual** with transition gating. RMSSD/SDNN are
still computed and logged for comparison.

---

## 2. Real-time loop (`MainActivity`)

A coroutine runs once per second (`delay(1000)`):

```kotlin
features = analyzer.analyze(hrValue, isSpeaking)
stepIntervals = analyzer.getStepIntervals()
tourManager.updateContext(features?.loadLevel ?: "Waiting...", isSpeaking)
dataManager.recordData(...)
```

- `hrValue` and `rrIntervals` arrive asynchronously via BLE notifications
  (`StateFlow`); RR batches are pushed into the analyzer as they arrive
  (`LaunchedEffect(rrIntervals) { analyzer.addRRIntervals(rrIntervals) }`).
- `analyze()` maintains all time-dependent state internally and advances it by the
  measured inter-tick interval `dt` (clamped to 0.1–10 s), so Bluetooth dropouts do not
  corrupt the dynamics models.

---

## 3. Input channels

### 3.1 BLE heart rate (`BleHeartRateManager`)

- Connects to any device advertising the standard **Heart Rate Service**
  (`0x180D`), subscribes to Heart Rate Measurement (`0x2A37`).
- Parses the flags byte: HR format (uint8/uint16), energy-expended field presence,
  RR-present bit.
- **HR (bpm):** published as-is to the app (this value is trustworthy from PPG —
  the armband heavily smooths it).
- **RR intervals:** transmitted in **1/1024 second** units per the Bluetooth spec;
  converted to milliseconds: `rr_ms = round(rr_raw × 1000 / 1024)`
  *(recent fix — previously the raw value was used, biasing all HRV numbers ~2.3 %)*.

### 3.2 RR artifact filter (`GaitAnalyzer.addRRIntervals`)

Each incoming RR (ms) is accepted only if:

1. **Plausibility:** `300 ms ≤ rr ≤ 2000 ms` (30–200 bpm), and
2. **Successive-difference:** `|rr − rr_prev_accepted| ≤ 30 % of rr_prev_accepted`
   (rejects missed/extra beats).

Accepted intervals fill a rolling window of the **last 60 intervals** (~60 s at 1 Hz
notifications). Rejected intervals do not enter the window.

### 3.3 Step detection (`GaitSensorManager`)

- Uses the hardware `TYPE_STEP_DETECTOR` sensor; each event appends a nanosecond
  timestamp to a rolling buffer of the **last 20 steps**.

### 3.4 Voice activity (`VoiceSensorManager`)

Runs a dedicated thread reading 8 kHz mono PCM in 1024-sample (~128 ms) chunks:

1. **Volume floor:** chunk RMS must exceed **400** (16-bit scale) — discards ambient
   background and distant conversations.
2. **Spectral dominance:** in-place Cooley–Tukey FFT; energy is summed in the human
   voice band (**300–3000 Hz**) vs. the rest of the spectrum. Chunk is classified as
   speech iff `voiceEnergy > 1.5 × noiseEnergy` (rejects broad-spectrum noise like wind).
3. **Debounce:** speech is declared iff **≥ 3 of the last 10 chunks** (~1.2 s window)
   were speech.
4. **Hangover:** `isSpeaking` stays true for **3 s** after the last detected speech
   (`isCooldown` flag is exposed during the hangover).

Output: boolean `isSpeaking` — used both as a load modifier and for TTS ducking.

### 3.5 Location (`LocationSensorManager`)

GPS fixes are logged and drawn on the map; **not used in scoring**.

---

## 4. Gait channel → `gaitLoadIndex`

From the step-timestamp buffer:

- **Walking state:** `isWalking = (time since last step < 5 s) AND (≥ 2 steps seen)`.
- Step intervals are gated to **300–1200 ms** (rejects detection glitches; the tight
  upper bound keeps standing/pacing from counting as walking).
- `avgStepTime` = mean of accepted intervals; `stepTimeVar` = standard deviation (ms).

```
gaitStepTimeScore = (avgStepTime − 500) / 200 × 100     // slower steps → higher
gaitVarScore      = (stepTimeVar − 20)  / 50  × 100     // more erratic → higher
gaitLoadIndex     = (gaitStepTimeScore + gaitVarScore) / 2
```

- **Stop rule:** if `!isWalking`, `gaitLoadIndex` is pinned to **25** ("Low") —
  standing still must not read as gait disturbance.
- Range notes: 500 ms step time (120 steps/min) scores 0 on the step-time term;
  20 ms variability scores 0 on the variability term. `null` if < 3 steps seen.

---

## 5. Cardiac channel → `hrLoadIndex` *(new)*

All timing-dependent state advances by `dt` every tick, even when HR is missing, so the
dynamics stay continuous across BLE dropouts. `hrLoadIndex` is `null` only if no HR has
ever been received.

### 5.1 Adaptive resting HR (`restingHr`)

```
if hr < restingHr:  restingHr += (hr − restingHr) × 0.15      // snaps down fast
else:               restingHr += (hr − restingHr) × 0.001     // near-frozen upward
```

Self-calibrating: any still moment (e.g. seated consent/instructions before the tour)
pulls the estimate to the participant's true resting HR. No manual baseline required.

### 5.2 HR smoothing

`hrSmoothed` is an EMA of the reported HR with time constant **τ = 15 s**
(`α = 1 − e^(−dt/τ)`), suppressing tick-to-tick PPG noise.

### 5.3 Cadence

`cadence = 60000 / avgStepTime` steps/min while `isWalking`, else **0**. Derived from
the existing step buffer — no additional sensor.

### 5.4 Physical strain score (Karvonen %HRR)

```
HRmax      = 208 − 0.7 × age          // Tanaka et al. (2001); age defaults to 25
hrr        = (hrSmoothed − restingHr) / (HRmax − restingHr)
strainScore= clamp(hrr / 0.50 × 100, 0, 100)      // 50 %HRR == 100 points
```

This is the established occupational-physiology measure of aerobic workload; it now
explicitly labels the HR channel's dominant component as *physical* strain.

### 5.5 Motion-corrected mental residual

The part of HR that locomotion cannot explain is treated as the candidate cognitive
signal:

```
hrTarget  = restingHr + a × cadence            // a = walkingHrCoefficient (default 0.30 bpm per step/min)
hrPred   += (hrTarget − hrPred) × min(dt/45 s, 1)   // one-pole cardiac kinetics, τ = 45 s
mentalHr  = hrSmoothed − hrPred                 // bpm, can be negative
```

The **kinetics model** is essential: real HR follows activity changes with a ~30–60 s
lag. Matching that lag in the prediction prevents pace transitions from leaking into
the residual as fake load (over-prediction on movement onset, under-prediction — a
spurious spike — after stopping).

**Transition gating (recovery window).** The residual is only interpretable in steady
state, and HR recovery after stopping is slower than cadence decay. Whenever

```
|cadence − cadence_prev| > 15 steps/min   OR   isWalking changed
```

a **60 s recovery window** starts, during which the mental channel is muted, then ramps
back in over the final **15 s**:

```
mentalWeight = clamp((15 − recoveryTimer) / 15, 0, 1)
mentalScore  = clamp(mentalHr / 8 bpm, 0, 1) × 100 × mentalWeight
```

The 8 bpm full scale reflects the literature effect size for mental load on HR above
physical baseline (~3–8 bpm). Negative residuals clamp to zero — an over-predicting
`a` degrades gracefully to a silent mental channel.

### 5.6 Cardiac fusion

```
hrLoadIndex = (strainScore + mentalScore) / 2      // null if no HR received yet
```

The channel is therefore ~5/6 physical (strain 1/4 of combined, HR-mean semantics) and
~1/12 cognitive by construction — consistent with the measured dominance of physical
load on HR (Makowiec-Dąbrowska et al. 1992) and the reliability limits found for
wearable HR during movement (Mach et al. 2022).

---

## 6. Fusion → combined index and level

```
combinedLoadIndex = (gaitLoadIndex + hrLoadIndex) / 2        // both present
                    = gaitLoadIndex ?: hrLoadIndex           // single-channel fallback
                    = null                                   // neither available → "Waiting..."
```

Level thresholds on the combined index:

| Range | Level |
|---|---|
| < 50 | Low |
| 50–100 | Medium |
| ≥ 100 | High |

**Voice modifier:** if `isSpeaking`, the level is bumped one tier (Low→Medium,
Medium→High, High→Overload). Rationale: talking while processing tour content is an
extra attentional demand. No bump in "Waiting...".

---

## 7. Tour adaptation (`TourManager.updateContext`)

In **auto mode** (dynamic condition):

| Level | TTS speed | Inter-line delay | Detailed script | Large-image mode |
|---|---|---|---|---|
| Low / Waiting | 1.0× | 0 ms | yes | no |
| Medium | 0.75× | 1500 ms | yes | yes |
| High | 0.75× | 1500 ms | no | yes |
| Overload | — | — | — | yes (and playback is paused) |

- TTS **ducking** while `isSpeaking` (participant voice takes precedence).
- When the level drops out of Overload and playback was paused, the tour **resumes
  automatically** at the next script line.
- **Static mode** (control condition): fixed at Low configuration, no ducking, no
  adaptation.

---

## 8. Data recording (`ExperimentDataManager`)

A sample is written when the state hash changes (event, `trigger: "event"`) or at least
1 s has elapsed (`trigger: "periodic"`). State hash covers `poi, loadLevel,
combinedLoadIndex, isAutoMode, isDetailed, isLargeImageMode, isSpeaking, scriptLine`.
File: `experiment_<timestamp>.json` in app-external storage; flushed every 5 samples and
on every state change.

**JSON schema per sample:**

| Field | Source |
|---|---|
| `timestamp` | wall clock (`yyyy-MM-dd'T'HH:mm:ss.SSSZ`) |
| `hr` | armband HR (bpm), `null` before first contact |
| `hrv_rmssd` | RMSSD of the filtered RR window (ms) — **logged, not scored** |
| `step_variance` | `stepTimeVar` (ms) |
| `mental_hr_bpm` | motion-corrected residual (bpm, pre-clamp) *(new)* |
| `physical_strain_score` | Karvonen strain score (0–100) *(new)* |
| `combined_load_index` | fused index (0–100+) |
| `load_level` | final level incl. voice bump |
| `poi`, `script_line` | tour position |
| `is_speaking` | voice channel output |
| `is_auto_mode`, `is_detailed_script`, `is_large_image_mode` | tour state |
| `lat`, `lon` | GPS |
| `trigger` | `event` \| `periodic` |

Note: `hrv_rmssd` semantics changed with the unit fix — old recordings are in
1/1024-s units (~2.3 % smaller) and pre-filter; do not mix cohorts when comparing.

---

## 9. Visualizer mapping (`visualizer/src/components/SensorCharts.tsx`)

Six per-variable charts (stacked, hover-synced via shared `syncId`, x-axes pixel-aligned)
plus a toggleable unified view. Series keys map to logged fields:

| Series | Field | Axis | Notes |
|---|---|---|---|
| Heart Rate (BPM) | `hr` | left | red |
| HRV (RMSSD) (ms) | `hrv_rmssd` | left | blue; diagnostic only now |
| Mental HR (Residual) (bpm) | `mental_hr_bpm` | left | orange; can be negative; the validation signal for `walkingHrCoefficient` |
| Step Variance | `step_variance` | right | green |
| Physical Strain (%HRR) | `physical_strain_score` | right | cyan |
| Combined Load Index | `combined_load_index` | right | purple |

Hovering any chart shows all series' values at that timestamp on every chart (own
series emphasized). Upload a session JSON or place it at `visualizer/data/latest_experiment.json`.

---

## 10. Constants reference

| Constant | Value | Meaning |
|---|---|---|
| `HR_SMOOTH_TAU_SEC` | 15 | EMA τ on measured HR |
| `HR_RESPONSE_TAU_SEC` | 45 | cardiac lag to activity (kinetics model) |
| `RESTING_HR_DROP_ALPHA` | 0.15 | resting-HR downward adaptation per tick |
| `RESTING_HR_RISE_ALPHA` | 0.001 | resting-HR upward drift per tick |
| `RECOVERY_WINDOW_SEC` | 60 | mental-channel mute after pace transitions |
| `MENTAL_RAMP_SEC` | 15 | ramp-in of mental channel at window end |
| `CADENCE_TRANSITION_STEPS_PER_MIN` | 15 | cadence jump that starts a recovery window |
| `STRAIN_FULL_SCALE_HRR` | 0.50 | %HRR mapped to 100 strain points |
| `MENTAL_HR_FULL_SCALE_BPM` | 8.0 | residual bpm mapped to 100 mental points |
| `walkingHrCoefficient` (a) | 0.30 | predicted HR rise per step/min of cadence |
| `restingHrSeed` | 70.0 | initial resting HR (self-corrects) |
| `participantAge` | 25 | for Tanaka HRmax (208 − 0.7·age) |
| Gait: step-time term | (−500)/200 | 500 ms step time = 0 points |
| Gait: variability term | (−20)/50 | 20 ms variability = 0 points |
| Stop rule | 25 | gait score while not walking |
| Level thresholds | 50 / 100 | Low/Medium/High boundaries |

---

## 11. What the experiment requires (and doesn't)

**Required: nothing new.** The model runs out of the box with defaults.

**Strongly recommended (zero instrumentation):**
1. Keep the participant **seated and quiet for 2–3 minutes** before the tour starts
   (consent/instructions window) so `restingHr` converges to the true resting floor.
2. **Note the participant's age** — currently only affects HRmax via the default; for a
   20–30 y cohort the effect on strain is ±3 %, so this is optional precision.

**Post-hoc tuning (from the first session's logs, in the visualizer):**
- If `mental_hr_bpm` is pinned at ≤ 0 while standing at POIs → lower
  `walkingHrCoefficient` (try 0.2). If it spikes during *steady* walking → raise it.
- If the tour never reaches "High" → lower `STRAIN_FULL_SCALE_HRR` (0.50 → 0.35), since
  the new channel is deliberately more conservative than the old HRV formula.

---

## 12. Rationale and references

- **Positive HRV–load sign (historical):** Solhjoo et al. 2019, *Sci Rep* 9:14668
  (`heartrate.pdf`) — intrinsic cognitive load correlated *positively* with SDNN/RMSSD
  (r ≈ 0.71–0.90); mean HR correlated negatively with performance. The HRV terms were
  removed for signal-quality reasons, not because of this paper's direction.
- **PPG HRV noise floor while moving:** wrist/forearm PPG RMSSD is reliable at rest but
  collapses under motion artifacts (observed 3–10 ms on this armband vs. normative
  resting RMSSD ≈ 19–75 ms, Nunan et al. 2010 meta-analysis).
- **Physical vs. mental HR attribution:** Makowiec-Dąbrowska et al. 1992 (physical
  workload dominates HR under combined load); Mach et al. 2022, *Applied Ergonomics*
  (wearable HR detects mental workload reliably only at low physical activity).
- **%HRR as physical strain:** Karvonen 1957; HRmax from Tanaka et al. 2001.
- **Wristband cognitive-load features (HR, RR, temp, EDA):** Borisov et al. 2021,
  *Computers in Human Behavior Reports* (`wristband.pdf`), CogLoad@UbiComp 2020.

## 13. Known limitations

1. **The mental residual is a slow, small signal** (a few bpm, τ ≈ 30 s+) — usable as a
   trend indicator during POI stops, not an event detector.
2. **PPG HR error while walking** (±3–5 bpm in validation literature) feeds both the
   strain and residual channels; the residual partly cancels common motion-driven HR
   error, but extreme cases will leak.
3. **RMSSD remains unusable while moving** on this armband; it may become usable at
   rest — check post-filter rest values in the visualizer before re-weighting it.
4. **Level thresholds were tuned for the old channel scales** — expect the index to run
   lower now; retune `STRAIN_FULL_SCALE_HRR` if needed (§11).
5. **No single-channel trust metric:** if the armband disconnects, the system silently
   falls back to gait-only scoring ("Waiting..." only before any HR arrives).
