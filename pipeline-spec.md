# CAMCSCHAR Demo — Real-Time Cognitive Load Pipeline Specification

**Status:** Implemented — **audio-first pivot v2 + frozen per-subject statistical/EWMA pipeline**.
**Last updated:** 2026-11-27
**Scope:** Android app (`app/src/main/kotlin/org/example/`), offline calibration tooling (`tools/`), and data schema consumed by `visualizer/`.

This document describes the complete real-time pipeline: manual pre-tour baseline
calibration, how raw sensor streams are acquired, normalized, smoothed via a bounded
stationary EWMA controller, how the load level drives the headphone-only adaptive tour,
and how telemetry is logged for the visualizer and pilot calibration audit.

---

## 1. Overview

The system estimates a participant's cognitive load in real time while they walk a
campus tour, and adapts a **hands-free headphone audio tour** (speech rate, inter-line
pausing, script density, and overload pause/recap) to that estimate. Load is
computed every **1 second** from three normalized components inside `StatisticalLoadModel` —
**within-subject gait Step-Time CV departure**, **sustained Silero VAD vocal duty cycle**,
and an **ACSM Moderate-to-Vigorous `%HRR` capacity-reserve hinge** — plus logged-only
diagnostics (`mental_hr_bpm`, HRV, ambient RF/acoustic, GPS). See `design-choices.md`
for literature justifications and citations.

**Audio-first pivot (v2, headphone/earphone-only):** audio playback exclusively targets
headphones/earphones (loudspeaker mode and ducking are out of scope) behind a
`PresentationMode` flag — `MULTIMODAL` (legacy v1 visuals), `AUDIO_FIRST` (minimal
eyes-free UI) or `AUDIO_ONLY` (no in-tour UI). Before the tour screen (`Start Tour`)
unlocks, `CalibrationScreen` enforces a manual 60-second quiet-walking baseline capture
(`BaselineCalibrator`). Details in §4–§7.

```
 Decathlon BLE armband          Phone step detector        Headphone inline mic
 (BLE Heart Rate Service)       (TYPE_STEP_DETECTOR)       (AudioRecord 16 kHz
        │                              │                    + Silero VAD onnx)
        ▼                              ▼                          ▼
 BleHeartRateManager            GaitSensorManager          VoiceSensorManager
  • HR (bpm)                     • step timestamps (ns)     • isSpeaking (debounced)
  • RR intervals (1/1024 s)      • Step-Time CV (%)         • speechDutyCycle10s
        │                              │                          │
        └──────────► GaitAnalyzer.analyze(hr, isSpeaking, duty) ◄─────────┘
                      + BaselineCalibrator (60s Median/MAD lock)
                                   │
                     ┌─────────────┼───────────────────────┐
                     ▼             ▼                       ▼
              zGait (CV)     zVoice (VAD duty)    ACSM >=40% HRR hinge
                     └─────────────┼───────────────────────┘
                                   ▼
                   Stationary Cognitive EWMA + 5s Dwell Hysteresis
                   → combinedLoadIndex & loadLevel (Low..Overload)
                     (mental_hr_bpm + RMSSD logged-only)
                                   │
              ┌────────────────────┼─────────────────────┐
              ▼                    ▼                     ▼
       TourManager           ExperimentDataManager   Visualizer (web)
       (adaptive tour)       (JSON logging)          (synced charts)
```

Key evolution vs. earlier heuristics:
- **HRV (SDNN/RMSSD)** remains logged-only (motion PPG sits at the ~3–10 ms noise floor).
- **Cadence-corrected `mental_hr_bpm`** (with stop-only recovery and asymmetric kinetics) is also **logged-only** rather than feeding the live EWMA filter, due to incline/cadence bias on campus stairs (`design-choices.md §5`).
- Raw population gait cutoffs (`500 ms` / `20 ms`) and instant `+1` speech bumps are replaced by per-participant **Median + MAD Step-Time CV** normalization, **sustained 10 s VAD duty cycle**, and a structurally bounded **`40–60% HRR`** reserve hinge.

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

Runs a dedicated thread reading 16 kHz mono PCM in 512-sample (32 ms) chunks:

1. **Silero VAD:** each chunk is scored by **Silero VAD** (16 kHz ONNX export
   with explicit state, ~1.2 MB, MIT) via ONNX Runtime. A chunk counts as speech
   iff speech probability > **0.5**. The trained model is robust to wind,
   traffic, footsteps and other broad-spectrum noise that the legacy band-ratio
   heuristic misclassified as speech; it replaces the old volume-floor +
   FFT heuristic, which is kept only as a fallback if the model fails to load.
2. **Debounce:** speech is declared iff **≥ 5 of the last 25 chunks** (~0.8 s
   window, i.e. ≥ 0.16 s of speech) were speech.
3. **Hangover:** `isSpeaking` stays true for **3 s** after the last detected speech
   (`isCooldown` flag is exposed during the hangover).

Output: boolean `isSpeaking` and rolling 10 s `speechDutyCycle10s` — running continuously across headphone/earphone sessions with zero loudspeaker TTS suppression or ducking. Non-speech chunks (`speechProb <= 0.5`) also feed `AcousticFrameAnalyzer` in volatile RAM to publish `ambientNoiseDbfs`, `ambientNoisePeakDbfs`, and `ambientMidbandRatio` (300–3000 Hz power fraction).

### 3.5 Location (`LocationSensorManager`)

GPS fixes are logged and drawn on the map; **not used in scoring**.

### 3.6 Ambient RF context (`AmbientScanManager`) — *Stage 1 telemetry-only*

- **BLE channel (`BleFilterCatalog` + `BleWindowAggregator`):** Scans continuously in `SCAN_MODE_LOW_POWER` using **70** Bluetooth SIG manufacturer Company IDs (`setManufacturerData` with 1-byte zeroed `[0x00]` masks) and **16** cross-brand 16-bit Service UUIDs (`setServiceUuid` + AD `0x16` `setServiceData` for Fast Pair `0xFE2C`, Eddystone `0xFEAA`, SmartTag `0xFD5A`/`FD69`, Tile `0xFEED`, Exposure `0xFD6F`, LE Audio), with automatic fallback (`full` → `core16` → 6-filter 10 s `batch` rotation) and app-wide `BleScanThrottler` (≤ 4 starts / 30 s). Includes an on-demand **10 s Screen-On Unfiltered Recall Probe** in Debug (`ble_probe_*`).
- **Wi-Fi AP channel (`WifiScanAggregator`):** 100% SSID-blind (`ScanResult.SSID` / `wifiSsid` are never read); logs AP density, strong AP count (`≥ -65 dBm`), 5 GHz/6 GHz ratio, RSSI mean/std, inter-scan $|\Delta\text{RSSI}|$, IEEE `0x02` LAA BSSID count, transient APs, `802.11mc` count, and `wifi_scan_age_ms` (`adb shell settings put global wifi_scan_throttle_enabled 0` lifts OS throttling on study phones).
- **Volatile privacy boundary (`EphemeralIdFilter`):** Raw MACs and BSSIDs are hashed immediately inside callbacks using `SHA-256(256-bit SecureRandom session salt || ID)` with a 180 s max RAM retention TTL; device names, manufacturer payload bytes, and raw audio are never stored or logged. Logged for post-hoc analysis only; **not fed into §6 scoring**.

---

## 4. Gait channel & Pre-Tour Baseline Calibration (`BaselineCalibrator`)

From the 20-step nanosecond buffer (automatically resetting stale buffers upon starting a new step after `≥ 4.0 s` idle):

- **Walking state:** `isWalking = (time since last step < 4.0 s) AND (≥ 2 steps seen)`.
- **Active stepping freshness:** `isActivelyStepping = isWalking AND (time since last step ≤ 1.5 s)` — while waiting out seconds `1.5–4.0` during a halt, gait departure holds neutral (`0.0`) instead of holding stale braking tails.
- Step intervals are gated to **300–1200 ms**, then filtered by a relative-median inlier band (**`0.65×median .. 1.45×median`**) whenever `≥ 4` intervals are present so 1–2 slow deceleration steps or missed beats do not spike SD.
- **Coefficient of Variation:** `stepCvPct = (stepTimeVar / avgStepTime) × 100` (while `isActivelyStepping` with `≥ 2` inlier intervals).

Before the tour screen unlocks, `CalibrationScreen` requires pressing **"Begin Calibration (60 s Walk)"** to collect `baselineTargetTicks = 60` calm silent walking ticks (`isActivelyStepping && !isSpeaking`), freezing each participant's baseline using outlier-robust statistics:

```
cv0      = median(stepCvSamples)
sigmaCv0 = max(1.4826 × median(|cv − cv0|), 0.40 %)   // variance floor 0.40 %
```

While uncalibrated (or under Debug bypass), healthy young-adult norms apply (`stepTime = 500 ms`, `cv0 = 1.5 %`, `sigmaCv0 = 0.5 %`). Standardized cognitive-motor departure is capped strictly below `High (4.0)`:

```
zGait = clamp((stepCvPct − cv0) / sigmaCv0, 0, 3.0)   // 0.0 when stopped / idle > 1.5 s
```

---

## 5. Cardiac channel (`%HRR` Reserve + Logged-Only Mental Residual)

All timing-dependent state advances by `dt` every tick, even when HR is missing.

### 5.1 Adaptive resting HR (`restingHr`) and smoothing

`restingHr` adapts asymmetrically (`α_drop = 0.15`, `α_rise = 0.001` from seed `70.0`), and `hrSmoothed` applies an EMA with **τ = 15 s** (`α = 1 − e^(−dt/τ)`).

### 5.2 Cadence and ACSM Physical Capacity Reserve (`strainReserveBonus`)

`cadence = 60000 / avgStepTime` steps/min while `isWalking`, else **0**. Aerobic strain uses Tanaka et al. (2001) `HRmax = 208 − 0.7 × age` and Karvonen `%HRR`:

```
hrrFraction = (hrSmoothed − restingHr) / max(HRmax − restingHr, 1.0)
strainScore = clamp(hrrFraction / 0.50 × 100, 0, 100)      // logged 0–100 diagnostic
```

For live cognitive-load reserve adjustment, `%HRR` applies an **ACSM Moderate (40%) → Vigorous (60%) piecewise hinge** structurally capped below `threshMedium (2.0)`:

```
strainReserveBonus = clamp((hrrFraction − 0.40) / 0.20, 0, 1.0) × 1.50
```

Light/moderate walking (`≤ 40% HRR`) contributes **0.0**; steep stairs alone (`≥ 60% HRR`) add at most `+1.50σ` (preserving `< Medium` unless concurrent gait or vocal departure occurs).

### 5.3 Exploratory mental HR residual (`mental_hr_bpm` — logged only)

```
hrTarget = restingHr + 0.30 × cadence
tau      = (hrTarget > hrPredicted) ? 20 s : 40 s          // fast vagal onset vs slower recovery
hrPredicted += (hrTarget − hrPredicted) × (1 − e^(−dt/tau))
mentalHr = hrSmoothed − hrPredicted
```

When transitioning walking → stopped (`lastIsWalking && !isWalking`), a **20 s recovery timer** (with `10 s` ramp-in on resuming) mutes the logged `mentalHrScore` to absorb the abrupt post-walk HR tail without resetting on continuous cobblestone pace shifts. **`mental_hr_bpm` is logged only and excluded from §6 EWMA scoring** due to incline/cadence bias on stairs (`design-choices.md §5`).

---

## 6. Fusion → Stationary EWMA & Hysteresis (`InnovationCusumController`)

Cognitive channels carry equal unit weights (`1.0×`), with per-channel ceilings ensuring neither gait (`max 3.0`) nor voice (`max 2.5`) alone reaches `High` or `Overload`:

```
vocalDuty = speechDutyCycle10s (or 0.50 if omitted & isSpeaking)
zVoice    = clamp((vocalDuty − 0.10) / 0.25, 0, 2.5)       // rejects <10% transient noise; continuous speech saturates at Medium
zCog      = zGait + zVoice
```

A stationary first-order **EWMA control filter** (`τ_ewma = 6.0 s`) tracks current steady-state load level without winding up indefinitely over time, and adds the bounded `%HRR` reserve boost:

```
alpha = 1 − e^(−dt / 6.0)
E_t   = E_{t−1} + alpha × (zCog − E_{t−1})
effectiveEvidence = E_t + strainReserveBonus
combinedLoadIndex = effectiveEvidence × 25.0                // 50 / 100 / 137.5+ scale parity
```

Level thresholds (`Waiting...` before first HR, ≥ 2 walking intervals, or sustained speech):

| Effective Evidence (`effectiveEvidence`) | `combinedLoadIndex` | Level |
|---|---|---|
| `< 2.0` | `< 50` | Low |
| `2.0 – 4.0` | `50 – 100` | Medium |
| `4.0 – 5.5` | `100 – 137.5` | High |
| `≥ 5.5` | `≥ 137.5` | Overload |

Upward transitions apply on threshold crossing; **downward transitions require `minDownDwellTicks = 5 s`** of sustained lower evidence before stepping down, eliminating boundary flicker.

---

## 7. Tour adaptation (`TourManager.updateContext`) — *v2 audio-first*

### 7.0 Presentation modes

Only presentation differs between modes — the load pipeline (§3–§6) and the POI
scripts are identical.

| Mode | Presentation |
|---|---|
| `MULTIMODAL` | Legacy v1 UI: map (minimap/enlarged), transcript, inline/large images (§7.6). Retained for backward comparison. |
| `AUDIO_FIRST` | Eyes-free by default: media notification + **large map** + collapsible researcher transcript; no inline images. **Launch default and primary study condition.** |
| `AUDIO_ONLY` | No in-tour UI at all; playback is controlled only via the media notification and headset button; phone stays in the pocket. |

The app **launches in `AUDIO_FIRST`**; the Debug tab switches modes.

Because the visual channel disappears, all adaptation must live in the audio channel.
The load level maps to an **`AudioTourParams`** object applied on every tick:

| Parameter | Type | Meaning |
|---|---|---|
| `speed` | float | TTS playback rate; applied live mid-utterance via the `TTSManager` `AudioTrack` path (no restart/queue-flush) |
| `pauseMs` | long | silence inserted between script lines |
| `density` | enum | `DETAILED` / `SIMPLIFIED` script selection |
| `recapOnResume` | bool | on playback resume, re-speak the last completed line before continuing |

### 7.1 Audio adaptation matrix (auto mode / dynamic condition)

| Level | speed | pauseMs | density | Overload / Recovery |
|---|---|---|---|---|
| Low / Waiting | 1.0× | 300 ms | detailed | — |
| Medium | 0.85× | 1500 ms | detailed | — |
| High | 0.75× | 2000 ms | simplified | — |
| Overload | — (playback paused) | — | — | recap on resume |

Differences vs. v1 and their rationale:

- **Medium is no longer a purely visual tier.** In v1, Medium differed from Low only by
  `isLargeImageMode`; with visuals gone, Medium gets its own audio profile — slower
  rate and longer inter-line gaps give processing time (the auditory analog of reducing
  visual clutter). Script density stays detailed until High.
- **High switches to simplified script density** at 0.75× speed with 2.0-second gaps to
  ease processing load under heavy cognitive and physical demand.
- **Low inserts a short natural pause (300 ms)** instead of v1's 0 ms — avoids
  line-concatenation artifacts; also applies to the static control so conditions stay
  symmetric.
- **Overload pauses playback** (v1 behaviour). On recovery the tour resumes
  automatically with a **one-line recap** — the last completed line is re-spoken —
  then continues (in audio modes there is no visual cue showing where the tour
  resumed).

### 7.2 Earcons (`AUDIO_FIRST` / `AUDIO_ONLY`)

| Event | Sound |
|---|---|
| POI arrival (geofence entry) | short arrival chime before the first line of the POI script |
| Transition into Overload | attention tone at the moment playback pauses |
| End of tour / end of script | soft closing tone |

In `MULTIMODAL` the visual UI carries these cues, so earcons are audio-mode-only.

### 7.3 Eyes-free playback control

- `TourService` provides a **media notification with `MediaSession`
  actions** (play/pause, previous/next); headset/lock-screen media buttons are routed
  through the same session via a `TourControlBridge` registered by `MainActivity`.
  Playback continues with the screen off (partial wake lock held while playing); the
  1 s analysis loop, BLE notifications and step detection keep running via the
  existing foreground-service machinery.
- Live `speed` changes act on the *currently playing utterance*
  (`PlaybackParams`), so level-driven adaptation is
  unobtrusive — no restarts, no queue flushes.

### 7.4 Content conventions

- Inline-image references become **spoken scene descriptions** (the narration
  describes what the participant is looking at); the `MULTIMODAL` parser keeps the
  current image format. Also pending content re-authoring.

### 7.5 Static mode (control condition)

Unchanged semantics: fixed at the Low configuration (1.0×, detailed script, no
recap), **no adaptation**; GPS geofencing still triggers POI content.

### 7.6 Legacy v1 behaviour (implemented; retained under `MULTIMODAL`)

| Level | TTS speed | Inter-line delay | Detailed script | Large-image mode |
|---|---|---|---|---|
| Low / Waiting | 1.0× | 0 ms | yes | no |
| Medium | 0.75× | 1500 ms | yes | yes |
| High | 0.75× | 1500 ms | no | yes |
| Overload | — | — | — | yes (and playback is paused) |

---

## 8. Data recording (`ExperimentDataManager`)

A sample is written when the state hash changes (event, `trigger: "event"`) or at least
1 s has elapsed (`trigger: "periodic"`). State hash covers `poi, loadLevel,
combinedLoadIndex, isAutoMode, isDetailed, isLargeImageMode, isSpeaking, scriptLine,
ttsSpeed, interLineDelay, isCheckpointActive, presentationMode`.
File: `experiment_<timestamp>.json` in app-external storage; flushed every 5 samples and
on every state change.

**JSON schema per sample:**

| Field | Source |
|---|---|
| `timestamp` | wall clock (`yyyy-MM-dd'T'HH:mm:ss.SSSZ`) |
| `hr` | armband HR (bpm), `null` before first contact |
| `hrv_rmssd` | RMSSD of the filtered RR window (ms) — **logged, not scored** |
| `step_variance` | `stepTimeVar` (ms) |
| `step_time_ms` | mean accepted step interval (ms) |
| `cadence_spm` | step cadence (steps/min while `isWalking`) |
| `is_walking` | walking activity gate (`true` / `false`) |
| `step_cv_pct` | within-window step-time Coefficient of Variation (`%`) |
| `hrr_fraction` | un-clamped Karvonen `%HRR` fraction |
| `gait_z_score` | bounded within-subject gait CV departure (`0–3.0σ`) |
| `voice_z_score` | bounded sustained 10 s VAD duty departure (`0–2.5σ`) |
| `strain_reserve_bonus` | ACSM `40–60% HRR` reserve boost (`0.0–1.5σ`) |
| `cusum_score` | smoothed stationary cognitive EWMA statistic (`E_t`, JSON key preserved) |
| `baseline_calibrated` | `true` once 60 s manual walking baseline locks |
| `baseline_step_cv_pct` | frozen baseline `cv0` (`%`) |
| `pilot_run_tag` | `study` \| `pilot_terrain_only` \| `pilot_terrain_distractors` |
| `mental_hr_bpm` | stop-gated cadence residual (bpm, **logged only**) |
| `physical_strain_score` | Karvonen strain diagnostic (0–100) |
| `combined_load_index` | `(E_t + strain_reserve_bonus) × 25.0` |
| `load_level` | hysteresis-filtered state (`Low`–`Overload`) |
| `poi`, `script_line` | tour position |
| `is_speaking` | voice channel output |
| `is_auto_mode`, `is_detailed_script`, `is_large_image_mode` | tour state (`is_large_image_mode` non-trivial only under `MULTIMODAL`) |
| `tts_speed` | active playback rate (×) *(new, v2)* |
| `inter_line_delay_ms` | pause inserted between script lines *(new, v2)* |
| `is_checkpoint_active` | checkpoint repetition flag (legacy / unused) *(new, v2)* |
| `presentation_mode` | `multimodal` \| `audio_first` \| `audio_only` *(new, v2)* |
| `lat`, `lon` | GPS |
| `ambient_sensing_enabled`, `env_enclosure_hint`, `env_busyness_tier` | ambient consent flag & derived context *(Stage 1, logging-only)* |
| `ble_filter_mode`, `ble_filter_count`, `ble_total_count`, `ble_near_count`, `ble_mid_count`, `ble_far_count`, `ble_mean_rssi`, `ble_max_rssi`, `ble_turnover_rate`, `ble_persistent_count`, `ble_packet_rate_hz`, `ble_phone_ecosystem_count`, `ble_audio_wearable_count`, `ble_tracker_beacon_count`, `ble_chipset_other_count` | RAM-salted BLE ambient aggregates *(Stage 1, zero MACs)* |
| `ble_probe_total_count`, `ble_probe_matched_count`, `ble_probe_coverage_ratio` | screen-on 10 s unfiltered BLE recall probe summary *(Stage 1)* |
| `wifi_ap_count`, `wifi_strong_ap_count`, `wifi_5ghz_ratio`, `wifi_mean_rssi`, `wifi_rssi_std`, `wifi_rssi_delta_abs_mean`, `wifi_laa_bssid_count`, `wifi_transient_ap_count`, `wifi_rtt_ap_count`, `wifi_scan_age_ms` | SSID-blind Wi-Fi AP aggregates *(Stage 1, zero SSIDs/BSSIDs)* |
| `ambient_noise_dbfs`, `ambient_noise_peak_dbfs`, `ambient_midband_ratio`, `speech_duty_cycle_10s` | non-speech mic floor & 10 s speech duty *(Stage 1, RAM-only)* |
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

New v2/Stage 1 fields (`tts_speed`, `inter_line_delay_ms`, `is_checkpoint_active`,
`presentation_mode`, and `ble_*` / `wifi_*` / `ambient_*` / `env_*`) are logged but not yet charted.

Hovering any chart shows all series' values at that timestamp on every chart (own
series emphasized). Upload a session JSON or place it at `visualizer/data/latest_experiment.json`.

---

## 10. Constants reference

| Constant | Value | Meaning |
|---|---|---|
| `HR_SMOOTH_TAU_SEC` | 15 | EMA τ on measured HR |
| `HR_ONSET_TAU_SEC` / `HR_RECOVERY_TAU_SEC` | 20 / 40 | asymmetric vagal onset vs parasympathetic decay lag |
| `RESTING_HR_DROP_ALPHA` | 0.15 | resting-HR downward adaptation per tick |
| `RESTING_HR_RISE_ALPHA` | 0.001 | resting-HR upward drift per tick |
| `RECOVERY_WINDOW_SEC` | 20 | logged-only `mentalHr` mute triggered on walk→stop transition |
| `MENTAL_RAMP_SEC` | 10 | ramp-in of logged `mentalHr` on walking resume |
| `baselineTargetTicks` | 60 | manual pre-tour calm walking baseline ticks (`CalibrationScreen`) |
| Normative `cv0` / `sigmaCv0` / floor | 1.5% / 0.5% / 0.4% | healthy young-adult warm-start CV & minimum dispersion |
| `stopTimeoutSec` / `stepFreshnessSec` | 4.0 s / 1.5 s | stop window and active step freshness gate |
| `stepOutlierLowRatio` / `HighRatio` | 0.65 / 1.45 | relative-median inlier filter against braking/missed beats |
| `voiceDutyDeadzone` / `voiceDutyScale` | 0.10 / 0.25 | 10 s Silero VAD duty-cycle z-score parameters |
| `hrrDeadzone` / `hrrFullScale` / cap | 0.40 / 0.60 / 1.50 | ACSM Moderate→Vigorous `%HRR` reserve hinge & strict cap |
| `zGaitMax` / `zVoiceMax` | 3.0 / 2.5 | single-channel ceilings (prevents single-channel `High`/`Overload`) |
| `ewmaTauSec` | 6.0 s | stationary cognitive EWMA filter time constant |
| Level thresholds (`Med / High / Ovl`) | 2.0 / 4.0 / 5.5 (50 / 100 / 137.5) | `effectiveEvidence` boundaries (×25 on `combinedLoadIndex`) |
| `minDownDwellTicks` | 5 | consecutive lower-tier ticks required before stepping down |
| `walkingHrCoefficient` (a) | 0.30 | predicted HR rise per step/min (logged-only residual) |
| `restingHrSeed` | 70.0 | initial resting HR (self-corrects) |
| `participantAge` | 25 | for Tanaka HRmax (208 − 0.7·age) |
| `AudioTourParams.AUDIO_LOW` (§7.1) | 1.0× / 300 ms | audio-mode Low pins (also the static control's pause) |
| `AudioTourParams.AUDIO_MEDIUM` (§7.1) | 0.85× / 1500 ms | audio-mode Medium pins |
| `AudioTourParams.AUDIO_HIGH` (§7.1) | 0.75× / 2000 ms / simplified | audio-mode High pins |
| Legacy MULTIMODAL pins (§7.6) | 1.0×·0 / 0.75×·1500 / 0.75×·1500 ms | v1 speed·delay per level |
| POI-arrival earcon lead-in | 900 ms | chime plays just before the first POI line (§7.2) |

---

## 11. What the experiment requires (and freezes)

1. Keep the participant **seated and quiet for 2–3 minutes** before starting the walk
   so `restingHr` stabilizes.
2. Complete the mandatory **manual 60-second quiet walk calibration** on `CalibrationScreen`
   before the `TourScreen` (`Start Tour`) unlocks.
3. **Fix the headphone kit and Android TTS engine/voice** across all sessions.
4. Before launching the `N = 30` cohort, audit bounded anti-chatter constants on the
   `2 × 5` researcher pilot split (`2 Terrain-Only`, `3 Terrain + Distractors`) using
   `tools/analyze_pilot_calibration.py`, then **freeze the APK identically across all 30 participants**.

---

## 12. Rationale and references

See `design-choices.md` for full literature rationales (`Tanaka et al. 2001`, Karvonen `%HRR` & ACSM intensity zones, Lambourne & Tomporowski 2010 exercise-cognition meta-regression, Michael Myrtek's Additional Heart Rate & its documented incline limitation, Mach et al. 2022, Makowiec-Dąbrowska et al. 1992, Nunan et al. 2010, and healthy young-adult dual-task gait CV norms).

## 13. Known limitations

1. **Logged-only `mental_hr_bpm` slope bias on inclines:** Cadence alone under-predicts exertion on stairs (`Segment 3`); `mental_hr_bpm` is therefore excluded from live adaptation and analyzed post-hoc on flat segments (`Segment 2`).
2. **PPG HR error while walking** (±3–5 bpm in validation literature) feeds diagnostic strain and residual logging.
3. **RMSSD remains unusable while moving** on this forearm PPG armband; retained for rest checks only.
4. **Silent cognitive load without motor or vocal signatures:** Load tasks producing neither gait CV instability, sustained speech, nor `> 40% HRR` reserve strain will not escalate the controller (addressed in `scenario.md` via paced vocal/dual-task segments).
5. **Headphone-only audio scope:** Loudspeaker mode is unsupported; Silero VAD runs continuously so speech over wired headphones triggers during active TTS playback.
6. **Device-dependent TTS voices** (§11): cross-participant comparison requires fixing a single hardware kit and TTS voice.
