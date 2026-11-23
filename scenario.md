# CAMCSCHAR Experimental Protocol

**Version:** 3.0 (Audio-First Progressive-Load Field Study)

**Target Systems:** Android App (`app/src/main/kotlin/org/example/`) and Telemetry Visualizer (`visualizer/`)

**Companion Files:** `pipeline-spec.md`, `AuditoryModality.kt`, `RetentionSurveyModel.kt`

**Setting:** Controlled Field Experiment, Sabancı University Campus

---

## 1. Overview and Hypotheses

### 1.1 Context

The CAMCSCHAR system delivers a hands-free, location-triggered cultural-heritage audio tour. In its v2 `AUDIO_FIRST` architecture, all adaptive behavior operates through the auditory channel—speech rate, inter-line pauses, script density, key-fact checkpoint repetition, and overload pause-with-recap—removing cross-modal visual adaptation from the experiment.

The walking route spans three campus Points of Interest (POIs) arranged along an upward gradient of physical and cognitive demand. Locations do not map rigidly one-to-one onto discrete cognitive-load states. Instead, task demands trend upward as the tour progresses, while individual differences in walking pace, cardiovascular response, and dual-task performance determine when and how long each participant occupies `Low`, `Medium`, `High`, or `Overload` states.

### 1.2 Research Objectives

1. Verify that progressively demanding walking and dual-task segments increase independent subjective mental effort and produce expected responses across cardiac strain, gait variability, and voice-activity channels.
2. Evaluate whether real-time auditory adaptation preserves retention of core tour facts under elevated load relative to static narration.


3. Quantify effects on subjective workload (NASA-TLX), memory omissions versus false-recognition errors, secondary-task performance, and peripheral detail loss when scripts simplify.



### 1.3 Hypotheses

* **H1 (Core Retention — Primary):** There will be a significant Condition (Auto vs. Static) × Segment (Segments 1–3) interaction on Core Retention (12 items; 4 per POI, present in both Detailed and Simplified scripts). Groups will perform similarly in Segment 1, whereas Auto Mode will achieve higher Core Retention in Segment 3.


* **H2 (Breadth–Retention Trade-Off — Secondary):** Because Auto Mode switches to Simplified scripts during `High` load, Auto Mode will exhibit lower retention on Detailed (peripheral) items in high-load segments, reflecting an intentional trade-off that preserves core facts.


* **H3 (Omissions vs. False Recognition):** On Core items in Segments 2 and 3, Auto Mode will yield a lower omission rate (Option E: *"I don't remember / Not sure"*) without inflating false-recognition errors.


* **H4 (Subjective Workload):** Post-tour Raw NASA-TLX overall workload, Mental Demand, and Temporal Demand will be lower in Auto Mode than in Static Mode, while Physical Demand remains comparable across groups.


* **H5 (Manipulation and Sensing Fidelity):** Independent per-segment mental effort ratings (Paas 9-point scale) and the proportion of time spent in elevated system tiers (`Medium`, `High`, `Overload`) will trend upward from Segment 1 to Segment 3, driven by the intended sensor channels.

---

## 2. Experimental Design and Apparatus

### 2.1 Study Design

The study uses a **two-group randomized between-subjects design** (Static Control vs. Auto Experimental) across a **three-segment progressive-load walking route**.

* **Between-subjects rationale:** Testing the same participant in both conditions would introduce severe learning and recall carryover on identical historical content.


* **Fixed route order:** Campus geography fixes the path order (Amphitheatre → Lake Memorial → Tosun Terzioğlu Garden), so within-subject changes across segments co-vary with POI content, fatigue, and recency. Primary causal inference therefore relies on **between-group comparisons within each segment**, where route order, narration content, and recall delay are identical.


* **Randomization:** Simple permuted block randomization (blocks of 4 or 6) ensures balanced group sizes without unnecessary stratification. Prior knowledge of Sabancı campus history is collected as a baseline 1–5 self-rating and included as a statistical covariate rather than proxied by years spent on campus.
* **Blinding:** Participants are blinded to condition and briefed that the study evaluates audio-guide comprehension while walking. The researcher sets `Static` or `Auto` in the Debug tab prior to locking the screen and delivers all secondary-task prompts verbatim from timed cue cards.



### 2.2 Participants and Power

* **Sample size:** Target $N = 30$ evaluable participants ($n = 15$ per group), recruiting up to 34 to cover potential exclusions (and enrolling additional participants if recruitment permits). At $\alpha = 0.05$ (two-tailed) and 80% power, $n = 15$ per group detects large between-group differences ($d \ge 1.06$) and medium-to-large Condition × Segment interactions ($f \ge 0.27$).


* **Eligibility:** Healthy adults aged 18–35; normal or corrected-to-normal hearing; unassisted walking and stair climbing; fluent English; no cardiovascular or musculoskeletal limitations; no cardiac-altering medications (e.g., beta-blockers); no caffeine or vigorous exercise within 2 hours prior to testing.



### 2.3 Hardware and Sensing Pipeline

A single standardized hardware kit (or identical phone models with identical OS and TTS builds) is used across all sessions to eliminate acoustic and sensor variance:

* **Host Smartphone (`AUDIO_FIRST` Mode):** Android 10+ (API 29+) running `CAMCSCHAR-demo` in `AUDIO_FIRST` mode with a fixed Android TTS engine and `en-US` voice. Although `AUDIO_FIRST` retains a large map and collapsible researcher transcript, the phone remains **screen-off in the participant's pocket** (or a provided hip pouch if the participant lacks pockets) throughout the walk; the foreground service maintains playback and sensor logging with the screen off. The researcher inspects the screen only during setup or troubleshooting.


* **Wired Headset:** A wired headset (open-acoustic or single-ear fit for ambient safety, preferably with an inline close-talk microphone) set to a comfortable volume before the walk. Wired delivery avoids Bluetooth HFP/SCO telephony profile switching when recording audio, prevents artifacts during live `PlaybackParams` rate changes, and reduces microphone pickup of the researcher's voice.


* **BLE Heart Rate Armband:** Decathlon optical PPG armband worn on the non-dominant upper forearm, streaming standard Bluetooth Heart Rate Service (`0x180D`, characteristic `0x2A37`). At 1 Hz, the frozen pipeline computes EMA-smoothed HR ($\tau = 15\text{ s}$), self-calibrating `restingHr` ($\alpha_{\text{drop}} = 0.15$, $\alpha_{\text{rise}} = 0.001$, seeded at 70 bpm), Karvonen `%HRR` physical strain (`STRAIN_FULL_SCALE_HRR = 0.50`, Tanaka $HR_{\max} = 208 - 0.7 \times \text{age}$, default age 25), and a cadence-predicted mental HR residual (`a = 0.30`, $\tau = 45\text{ s}$, 60 s recovery mute and 15 s ramp-in after cadence changes $> 15\text{ steps/min}$ or walking-state changes, 8 bpm full scale). Artifact-filtered RR intervals (converted from $1/1024\text{ s}$ to ms, gated to 300–2000 ms and $\le 30\%$ successive difference) are logged for diagnostic RMSSD analysis only and excluded from scoring.


* **Voice Activity Detection:** 16 kHz mono PCM audio sampled in 512-sample (32 ms) chunks and scored by Silero VAD (ONNX; speech probability $> 0.5$, $\ge 5$ of last 25 chunks debounce [~0.8 s], 3 s hangover). Voice activity bumps the load level up one tier; TTS ducking is disabled (100% volume).


* **Step and Location Sensing:** Hardware `TYPE_STEP_DETECTOR` (rolling 20-step nanosecond buffer, valid intervals 300–1200 ms, stop rule pinning `gaitLoadIndex` to 25 after 5 s without steps) and GPS geofencing.



### 2.4 Experimental Conditions

Both conditions use identical GPS geofence triggers, POI arrival earcons (900 ms lead-in), closing earcons, and `AUDIO_FIRST` presentation. All 12 Core quiz facts appear in both `DETAILED` and `SIMPLIFIED` scripts and are marked with a leading `*` (`ScriptLine.isKeyFact`) so checkpoint repetition targets tested core content.

| Condition / Load State | TTS Speed | Inter-Line Pause | Script Density | Key-Fact Checkpoint (`*`) | Overload Behavior |
| --- | --- | --- | --- | --- | --- |
| Static (Control) | 1.0×

 | 300 ms

 | Detailed

 | Off

 | None

 |
| Auto — Low / Waiting | 1.0×

 | 300 ms

 | Detailed

 | Off

 | None

 |
| Auto — Medium | 0.85×

 | 1500 ms

 | Detailed

 | Off

 | None

 |
| Auto — High | 0.75×

 | 2000 ms

 | Simplified

 | On (repeats `*` line once)

 | None

 |
| Auto — Overload | Paused

 | — | — | — | Pause + earcon; 1-line recap on resume

 |

---

## 3. Experimental Scenario and Procedure

### Phase 0: Screening and Seated Baseline (4–5 min)

1. Seated quietly near the Amphitheatre entrance, the participant provides informed consent and records age, self-rated prior knowledge of Sabancı campus history (1–5), and recent caffeine or exercise.


2. The researcher fits the BLE armband on the non-dominant forearm, connects the wired headset, plays a brief neutral TTS sample to set comfortable volume, and verifies live HR, step detection, and Silero VAD in the Debug tab.


3. The participant sits quietly without speaking for **3 minutes** so `restingHr` and `hrSmoothed` converge to true physiological resting baseline. The researcher sets the assigned condition (`Static` or `Auto`), starts logging, locks the screen, and places the phone in the participant's pocket (or provided hip pouch).


4. The participant is instructed to walk naturally along the guided route, perform secondary tasks accurately without ignoring the audio guide, and **keep walking slowly within the POI zone accompanied by the researcher** if narration is still playing near the end of a path. Continuing to walk prevents premature geofence exit while avoiding the 5-second stationary stop rule (`!isWalking`), which would artificially drop gait load to 25 and mute the cardiac mental residual for 60 s.



### Segment 1: Amphitheatre — Baseline Ambulation

* **Geofence:** 40.89321° N, 29.37917° E (radius: 80 m).


* **Terrain and Task:** Smooth, gently sloping downhill paved walkway; participant walks silently at a comfortable self-selected pace with no secondary task.


* **Expected Profile:** Low `%HRR` physical strain, regular stride timing, and inactive Silero VAD, establishing an unencumbered baseline predominantly in `Low`.


* **End-of-Segment Check:** Immediately after the closing earcon, the researcher records a verbal **Paas 9-point Mental Effort Rating** (1 = *very, very low*, 9 = *very, very high*).

### Segment 2: Lake Memorial — Conversational Dual-Task

* **Geofence:** 40.89288° N, 29.37551° E (radius: 60 m).


* **Terrain and Task:** Level paved path along the campus lake. Across the active narration window, the researcher delivers **5 standardized open-ended prompts** spaced evenly every ~15–20 s (stopping once the script ends), covering everyday non-tour topics (commute, favorite campus meal, weekend plans, weather, study habits) requiring 1–2 sentence spoken answers.


* **Controls:** The researcher walks ~1.5 m behind/beside the participant and keeps prompts brief (~2 s) so Silero VAD primarily reflects participant speech. Prompt completion and response coherence are logged on a structured sheet.
* **Expected Profile:** Modest physical strain on level terrain paired with elevated Silero VAD duty cycle and mild gait variability, trending Auto Mode upward into `Medium` (0.85× speed, 1500 ms pauses, Detailed script) while Static Mode plays unadapted.


* **End-of-Segment Check:** Verbal **Paas 9-point Mental Effort Rating**.

### Segment 3: Tosun Terzioğlu Garden — Locomotor and Working-Memory Dual-Task

* **Geofence:** 40.89058° N, 29.37719° E (radius: 80 m).


* **Terrain and Task:** One continuous script segment spanning the garden's uneven cobblestone/stepping-stone pathways and adjacent incline/stairs (mandatory handrail contact on stairs, with the researcher walking directly alongside for safety). Throughout active narration, the participant performs **paced aloud Serial 3s subtraction** from a standardized three-digit seed (e.g., 200 → 197 → 194…), speaking one number every ~4–5 seconds.


* **Task Rationale:** Paced Serial 3s imposes sustained working-memory and vocal interference without causing total articulatory suppression (which rapid Serial 7s risks, creating retention floor effects across both groups). The researcher logs total responses and arithmetic accuracy (%) to check for dual-task shedding.
* **Expected Profile:** Irregular footing, elevation gain, and aloud counting drive concurrent increases in gait variability, `%HRR` strain, and voice activity. Load trends toward `High` (triggering Simplified script density, 0.75× speed, 2000 ms pauses, and `*` key-fact checkpoint repetition in Auto Mode) and transient `Overload` episodes when peak locomotor/cardiac demand and speech coincide (pausing playback with an overload earcon and resuming with a one-line recap once load recovers). Static Mode continues unadapted throughout.


* **End-of-Segment Check:** Verbal **Paas 9-point Mental Effort Rating**.

### Phase 4: Post-Tour Evaluation and Debrief (Seated, 8–10 min)

Upon completion of the final script, `tourFinishedEvent` flushes telemetry and presents `RetentionSurveyScreen.kt` while the participant sits quietly:

1. **Raw NASA-TLX:** Six sliders (0–100 in 5-point increments) measuring Mental Demand, Physical Demand, Temporal Demand, Performance (0 = Good, 100 = Poor), Effort, and Frustration; composite RTLX is the unweighted mean of the six subscales.


2. **Cultural-Heritage Retention Quiz:** 20 multiple-choice items with 4 factual options (A–D) plus Option E (*"I don't remember / Not sure"*).


3. **Structured Debrief (2 min):** Two standardized open-ended questions (*"Did you notice any changes in how the audio guide spoke or behaved during the walk?"* and *"Which part of the tour was hardest to follow, and why?"*) recorded for qualitative context.
4. **Telemetry Export:** Synchronized 1 Hz and state-change JSON logs (`experiment_<timestamp>.json`) are saved in app-external storage alongside survey records.



---

## 4. Measures and Telemetry

### 4.1 Process Measures

* **Per-segment mental effort:** Paas 9-point scale collected immediately after Segments 1, 2, and 3 as an independent manipulation check.
* **Secondary-task performance:** Segment 2 prompt completion (0–5); Segment 3 Serial 3s count and accuracy (%).

### 4.2 Retention Quiz Structure

The 20-item quiz separates invariant core facts, detailed trade-off items, and attention checks:

| Segment | Core Items (Primary DV, 12 total)

 | Detailed Items (Trade-Off DV, 6 total)

 | Attention Traps (2 total)

 |
| --- | --- | --- | --- |
| 1. Amphitheatre | Q1–Q4: Construction year (2003), area (5,300 m²), capacity (4,000), unified graduation ceremony

 | Q5–Q6: Anatolian open-air inspiration, skateboarding events

 | — |
| 2. Lake Memorial | Q7–Q10: Former gravel pit, rainwater/wastewater refill, seagulls bringing fish eggs, Class Brick ceremony

 | Q12–Q13: Clay/gravel watertight lining, flowing water vs. solid brick metaphor

 | Q11: Direct instruction check

 |
| 3. Tosun Terzioğlu Garden | Q14–Q17: >170,000 campus plants, rector memorizing Latin tree names, Komatsu Sakura donation (2007–2009), ~2,000 trees / 7 species

 | Q19–Q20: Sakura Stone Monument origin (Gujyo, Japan), calligrapher (Toshitaka Hagiwara)

 | Q18: Direct instruction check

 |

**Derived quiz metrics:**

* **Core Retention Score:** Correct answers out of 12 (4 per segment).


* **Detailed Retention Score:** Correct answers out of 6 (2 per segment).


* **Omission Rate (%):** Share of Option E responses, computed separately for Core and Detailed sets.


* **False-Recognition Error Rate (%):** Share of incorrect non-E responses, computed separately for Core and Detailed sets.


* **Response Timing:** Silently logged per-item first-click latency, last-click latency, and dwell time (ms).



### 4.3 Sensor and System Telemetry (`ExperimentDataManager`)

Samples are logged every 1 s (`periodic`) and on every state change (`event`) into `experiment_<timestamp>.json`:

* **Physiological and locomotor:** `hr`, `hrv_rmssd` (diagnostic only), `physical_strain_score`, `mental_hr_bpm`, `step_variance`, `is_speaking`.


* **System and adaptation state:** `combined_load_index`, `load_level`, `is_auto_mode`, `is_detailed_script`, `is_large_image_mode`, `tts_speed`, `inter_line_delay_ms`, `is_checkpoint_active`, `presentation_mode`, `poi`, `script_line`.


* **Spatial and logging metadata:** `timestamp`, `lat`, `lon`, `trigger`.



---

## 5. Exclusion Rules and Statistical Analysis

### 5.1 Pre-Registered Exclusions

Applied identically across groups before unblinding:

1. **Attention traps:** Scoring 0/2 on Q11 and Q18 excludes the participant from quiz analyses (1/2 is retained in primary analysis and flagged for sensitivity verification).


2. **BLE dropout:** Because the pipeline silently falls back to gait-only scoring when HR drops, sessions where `hr` is `null` or frozen for $> 15\%$ of active POI narration are excluded from per-protocol analysis and reported alongside an intention-to-treat check.


3. **Protocol failure:** Audio failure, missed geofence ($> 1$ POI), or inability to complete Segment 3 safely.

### 5.2 Manipulation and Fidelity Checks (H5)

* **Subjective effort gradient:** Friedman test (or one-way repeated-measures ANOVA if normal) on Paas ratings across Segments 1–3, with Holm-corrected post-hoc comparisons verifying Segment 1 < Segment 2 < Segment 3.
* **Sensor and tier progression:** Repeated-measures comparisons across segments for `physical_strain_score`, `step_variance`, Silero VAD duty cycle (`% time is_speaking == true`), and percentage of active narration spent in `Low`, `Medium`, `High`, and `Overload`.
* **Secondary-task equivalence:** Welch's $t$-test or Mann-Whitney $U$ comparing Segment 2 prompt completion and Segment 3 Serial 3s count/accuracy across groups to confirm neither group shed secondary-task effort.

### 5.3 Retention Analyses (H1 and H2)

* **Primary Core Retention model (H1):** Item-level binomial Generalized Linear Mixed-Effects Model (GLMM, logit link) across the 12 Core items:

$$\text{logit}(P(\text{Correct}_{ij})) = \beta_0 + \beta_1 \text{Condition}_i + \beta_2 \text{Segment}_j + \beta_3 (\text{Condition}_i \times \text{Segment}_j) + \beta_4 \text{PriorKnowledge}_i + u_{\text{participant}, i} + u_{\text{item}, j}$$

followed by Holm-adjusted planned contrasts comparing Auto vs. Static within each segment.

* **Non-parametric check:** Aligned Rank Transform (ART) 2 × 3 Mixed ANOVA on per-segment Core totals (0–4), reported with means, SDs, and effect sizes (Cohen's $d$ / Cliff's $\delta$).
* **Detailed Retention trade-off (H2):** Separate GLMM and Mann-Whitney $U$ comparison on the 6 Detailed items (overall and within Segment 3).
* **Exploratory dose–response:** Regression within Auto Mode testing whether individual adaptation exposure (proportion of active narration spent in `Medium`, `High`, or `Overload`) predicts Core Retention after controlling for prior knowledge and mean physical strain.

### 5.4 Error Decomposition (H3) and Workload (H4)

* **Omissions vs. false recognition (H3):** Per-participant Core Omission Rate and Core False-Recognition Error Rate compared between conditions via Welch's $t$-test or Mann-Whitney $U$.
* **Subjective workload (H4):** Composite Raw NASA-TLX compared via Welch's $t$-test (or Mann-Whitney $U$ if Shapiro-Wilk $p < .05$). Targeted subscales (Mental Demand, Temporal Demand, Frustration) tested with Holm-Bonferroni correction. Physical Demand comparability is reported via effect size ($d$), 95% CI, and TOST equivalence bounds ($\Delta = \pm 15$ TLX points) rather than interpreting a non-significant $p$-value as proof of equivalence.

---

## 6. Validity Controls and Limitations

* **Fixed POI order and recency:** Primary conclusions rely on within-segment Auto vs. Static contrasts where order, content, and recall delay are identical; fixed route progression is documented as a study limitation.
* **Core vs. Detailed fact separation:** Separating Core items (12, invariant across script variants and tagged `*`) from Detailed items (6, trade-off metric) prevents penalizing Auto Mode for omitted peripheral text.


* **Frozen pipeline thresholds:** Because `AUDIO_FIRST` thresholds are inherited from multimodal tuning and the cardiac model is conservative, raw indices may run lower and `Overload` episodes may occur sparsely; actual tier occupancy and exploratory dose–response exposure are logged and reported transparently.


* **Non-circular manipulation check:** Per-segment Paas ratings and logged secondary-task accuracy validate stressor intensity independently of `combined_load_index`.
* **Narration overrun handling:** Having participants walk slowly within the POI zone if narration extends past the path ensures 100% script delivery without triggering the 5-second stationary stop rule; total playback duration per POI is logged as an adaptive mechanism.


* **Blinding and prior knowledge:** Single-blind participant handling, verbatim researcher cue cards, permuted block randomization, and the baseline campus-history knowledge covariate control allocation and expectancy bias.
* **Hardware and sensor fidelity:** Pocket/hip-pouch screen-off carry, a wired headset, a fixed Android TTS voice, a 3-minute seated baseline, exclusion of noisy motion-PPG RMSSD from scoring, and BLE-dropout screening control measurement variance.