# CAMCSCHAR Experimental Protocol & A/B Testing Specification

**Document Version:** 2.0 (Post Audio-First Pivot & Cardiac Model Refactor)  
**Target Systems:** Android Application (`app/src/main/kotlin/org/example/`) & Evaluation Telemetry (`visualizer/`)  
**Companion Documents:** [pipeline-spec.md](file:///home/Alar/Desktop/projects/CAMCSCHAR-demo/pipeline-spec.md), [AuditoryModality.kt](file:///home/Alar/Desktop/projects/CAMCSCHAR-demo/app/src/main/kotlin/org/example/AuditoryModality.kt), [RetentionSurveyModel.kt](file:///home/Alar/Desktop/projects/CAMCSCHAR-demo/app/src/main/kotlin/org/example/RetentionSurveyModel.kt)  
**Ethics & Scope:** Controlled Human-Subjects Field Experiment (Sabancı University Campus Walking Route)

---

## 1. Theoretical Framework, Objectives & Hypotheses

### 1.1 Context & Pivot Overview
The Context-Aware Multimodal Content Selection (CAMCSCHAR) system delivers a hands-free, location-aware cultural-heritage audio tour. Under the **v2 audio-first pivot**, the system operates eyes-free: the legacy visual layer (minimap, inline archival images, text transcript) is disabled during the tour, and all adaptive capacity is concentrated in the **auditory channel** (speech rate, inter-line pausing, information density, and key-fact repetition checkpoints). This eliminates the cross-modal confound present in visual-auditory systems, allowing retention and workload differences to be attributed directly to adaptive auditory pacing.

### 1.2 Research Objectives
1. To evaluate whether real-time multimodal estimation of cognitive load (fusing physical strain, motion-corrected cardiac residual, gait step-time variance, and voice activity) can reliably detect environmental and task-induced cognitive stress during ambulation.
2. To assess whether dynamically adapting speech parameters and information structure preserves factual knowledge retention and mitigates subjective cognitive overload under concurrent physical and cognitive demands.

### 1.3 Hypotheses
*   **Primary Retention Hypothesis ($H_{1}$):** Participants in the **Auto Mode (Experimental)** condition will achieve significantly higher post-tour factual retention scores than those in the **Static Mode (Control)** condition. This advantage will be most pronounced for key facts presented during high cognitive load segments (Phase 3), where simplified scripts and checkpoint repetitions actively mitigate working memory degradation.
*   **Subjective Workload Hypothesis ($H_{2}$):** Post-tour NASA-TLX scores for **Mental Demand**, **Temporal Demand**, and **Mean Workload** will be significantly lower in the Auto Mode condition compared to Static Mode. Physical Demand will show no significant difference between conditions, as both groups traverse the identical physical route.
*   **Information Omission Hypothesis ($H_{3}$):** Participants in Auto Mode will exhibit a significantly lower **omission rate** (selecting *"I don't remember / Not sure"*) on core facts compared to Static Mode, indicating reduced memory retrieval failure.
*   **Physiological Fidelity Check ($H_{4}$):** The multimodal cognitive load index (`combined_load_index`) and physiological strain will demonstrate a statistically significant step-wise increase across the three test phases, validating the environmental stressor manipulation.

---

## 2. Experimental Design & Apparatus

### 2.1 Study Design
A **Between-Subjects Randomized Controlled Design** ($N = 30$) is employed:
*   **Control Group ($n = 15$):** **Static Mode** — Fixed delivery regardless of estimated load. The detailed cultural-heritage script is spoken at continuous 1.0× speed with baseline 300 ms pauses, detailed information density, no checkpoint repetitions, and no overload pause/recap.
*   **Experimental Group ($n = 15$):** **Auto Mode (Dynamic)** — Real-time adaptation driven by the 1 Hz cognitive-load pipeline (§3–§6 of `pipeline-spec.md`). Delivery dynamically adjusts between *Low*, *Medium*, *High*, and *Overload* tiers.

*Methodological Justification:* A between-subjects design prevents the severe learning and carryover effects inherent in within-subjects cultural-heritage retention testing, where hearing factual answers in the first condition directly contaminates recall in subsequent trials.

### 2.2 Participants
*   **Sample:** 30 healthy adult volunteers (ages 18–35).
*   **Inclusion Criteria:** Normal or corrected-to-normal hearing; unassisted ambulation; fluent in English (the language of narration and testing).
*   **Exclusion Criteria:** Known cardiovascular conditions or musculoskeletal limitations that prevent walking or climbing stairs; beta-blocker or cardiac-altering medications.
*   **Stratification:** Participants are randomly assigned to groups while balancing for age and self-reported campus familiarity.

### 2.3 Hardware & Sensor Configuration
1.  **Host Smartphone:** Android device running Android 10+ (API 29+). Held in hand screen-down or placed in an accessible pocket.
2.  **BLE Heart Rate Monitor:** Decathlon BLE optical heart rate armband worn securely on the upper forearm of the non-dominant arm. Connects via Bluetooth GATT Standard Heart Rate Service (`0x180D`), transmitting real-time smoothed heart rate (bpm) and RR intervals.
3.  **Earphones:** Standard stereo earbuds (wired or low-latency Bluetooth) delivering TTS audio while preserving sufficient ambient acoustic transparency for participant safety.
4.  **Hardware Step Detector:** Built-in Android `TYPE_STEP_DETECTOR` reporting step timestamps at nanosecond precision.
5.  **Microphone Sensor:** Device microphone reading 8 kHz mono PCM, feeding a 1024-sample FFT voice activity detection pipeline (300–3000 Hz band dominance, RMS > 400, 3-of-10 debounce, 3-second hangover).
6.  **Location Sensor:** Android fused location provider (GPS/GNSS) with geofencing thresholds defined in [poi-vertices.geojson](file:///home/Alar/Desktop/projects/CAMCSCHAR-demo/app/src/main/assets/poi-vertices.geojson) and loaded by [PoiRepository.kt](file:///home/Alar/Desktop/projects/CAMCSCHAR-demo/app/src/main/kotlin/org/example/PoiRepository.kt).

### 2.4 Software Configuration & Presentation Mode
*   **Application Build:** `CAMCSCHAR-demo` (release-ready debug APK).
*   **Presentation Mode:** Standardized on `AUDIO_FIRST` (launch default). Eyes-free execution supported by `TourService` with media-session notifications; in-tour visual UI is hidden from the participant.
*   **TTS Standardization:** Fixed Android system TTS engine with standardized voice pitch, rate baseline, and language setting (`en-US`) across all devices to prevent acoustic variability confounds.
*   **Auditory Configuration Matrix:** Managed via [AuditoryModalityConfig](file:///home/Alar/Desktop/projects/CAMCSCHAR-demo/app/src/main/kotlin/org/example/AuditoryModality.kt):
    *   *Static Base:* 1.0× speed, 300 ms inter-line pause, detailed script, no checkpoints, no overload pause.
    *   *Dynamic Low:* 1.0× speed, 300 ms pause, detailed script, no checkpoints.
    *   *Dynamic Medium:* 0.85× speed, 1500 ms pause, detailed script, no checkpoints.
    *   *Dynamic High:* 0.75× speed, 2000 ms pause, **simplified script**, **checkpoint repetition active** (repeats key facts once before advancing).
    *   *Dynamic Overload:* Playback paused, Overload attention earcon plays, recap on resume.
    *   *Earcons:* Arrival earcon (900 ms lead-in before line 1), Overload tone, End-of-tour chime.
    *   *Voice Adaptation Policy:* TTS audio ducking is **disabled** (retained at 100% volume); voice activity operates strictly as an attentional demand modifier, bumping the calculated load level one tier higher.

---

## 3. Experimental Protocol & Standardized Scenario Walkthrough

```
  ┌───────────────────────────────────────────────────────────────────────────┐
  │ Phase 0: Baseline & Calibration (2-3 min seated quiet consent/rest)       │
  │ • Armband fitting • Resting HR convergence • Baseline step test           │
  └─────────────────────────────────────┬─────────────────────────────────────┘
                                        │
                                        ▼
  ┌───────────────────────────────────────────────────────────────────────────┐
  │ Phase 1: Amphitheatre (Low Cognitive Load)                                │
  │ • Downhill paved walking • Low gait variance • Silent baseline speech     │
  │ • Auto Mode: 1.0x speed, 300 ms pause, Detailed Script                   │
  └─────────────────────────────────────┬─────────────────────────────────────┘
                                        │
                                        ▼
  ┌───────────────────────────────────────────────────────────────────────────┐
  │ Phase 2: Lake Memorial (Medium Cognitive Load)                            │
  │ • Flat shoreline walk • Standardized 3-question researcher interview      │
  │ • Speech FFT triggers Voice Modifier -> bumps load to Medium              │
  │ • Auto Mode: 0.85x speed, 1500 ms pause, Detailed Script                 │
  └─────────────────────────────────────┬─────────────────────────────────────┘
                                        │
                                        ▼
  ┌───────────────────────────────────────────────────────────────────────────┐
  │ Phase 3: Tosun Terzioğlu Garden (High Cognitive Load & Overload)          │
  │ ├─ Part 3A (High CL): Uneven stone paths + Serial 7s subtraction          │
  │ │  • Gait variance spikes + speech active -> CL High                     │
  │ │  • Auto Mode: 0.75x speed, 2000 ms pause, Simplified Script + Checkpoints│
  │ └─ Part 3B (Overload): Stair ascent + rapid verification queries          │
  │    • %HRR jump + continuous speech -> Overload                            │
  │    • Auto Mode: Immediate TTS pause + earcon -> Top rest -> Recap resume  │
  └─────────────────────────────────────┬─────────────────────────────────────┘
                                        │
                                        ▼
  ┌───────────────────────────────────────────────────────────────────────────┐
  │ Phase 4: Post-Tour Evaluation (RetentionSurveyScreen)                     │
  │ • 6 NASA-TLX dimensions (0-100) • 20-item Retention Quiz (5 options)     │
  │ • Automated JSON telemetry export to local downloads and research server  │
  └───────────────────────────────────────────────────────────────────────────┘
```

### Phase 0: Pre-Trial Baseline & Calibration (Seated, 2–3 Minutes)
1.  **Informed Consent & Briefing:** Participant is seated quietly in a shaded waiting area near the Amphitheatre entrance.
2.  **Instrumentation:** The researcher secures the Decathlon BLE armband to the participant's forearm, verifies BLE connection in the app's Debug tab, checks battery level, and places earphones.
3.  **Adaptive Baseline Settling:** The participant remains seated and relaxed for 2–3 minutes while reviewing experimental instructions. This allows the adaptive resting heart rate tracker (`restingHr`, initialized at 70.0 bpm) to rapidly converge downward ($\alpha = 0.15$) to the subject's true physiological resting baseline.
4.  **Demographics & Mode Verification:** The participant's age is verified (calibrating $HR_{max} = 208 - 0.7 \times age$). The researcher sets the assigned study condition (`Static` or `Dynamic`) in the application and initiates recording.

---

### Phase 1: Amphitheatre — Baseline Ambulation (Low Cognitive Load)
*   **Geofence Coordinates:** $40.89321^\circ\text{ N}, 29.37917^\circ\text{ E}$ (Radius: 80 m).
*   **Physical Path:** Paved, gentle downhill walkway descending toward the Amphitheatre plaza.
*   **Stressor Manipulation:** None (baseline walking). Participant walks at their natural self-selected pace and listens without distraction.
*   **Expected Sensory State:**
    *   *Cardiac:* Heart rate remains near resting baseline; physical strain score $< 20$; mental HR residual near zero.
    *   *Gait:* Cadence steady (~100–115 steps/min); step interval within 450–550 ms; step-time variability $< 20$ ms (`gaitVarScore` near zero).
    *   *Voice:* Ambient noise below the 400.0 RMS threshold; no participant speech.
*   **Calculated Cognitive Load:** **Low** (`combined_load_index` $< 50$).
*   **System Action:**
    *   *Arrival:* Geofence entry triggers the POI arrival earcon, followed by a 900 ms silence lead-in before the first script line.
    *   *Control Group (Static):* Detailed Amphitheatre script spoken at 1.0× speed with 300 ms inter-line pauses.
    *   *Experimental Group (Auto):* Identical delivery (1.0× speed, 300 ms pause, detailed script).

---

### Phase 2: Lake Memorial — Standardized Verbal Task (Medium Cognitive Load)
*   **Geofence Coordinates:** $40.89288^\circ\text{ N}, 29.37551^\circ\text{ E}$ (Radius: 60 m).
*   **Physical Path:** Level asphalt running track bordering the campus lake.
*   **Stressor Manipulation (Standardized Verbal Interview):** Upon entry into the Lake Memorial geofence, the accompanying researcher engages the participant in a standardized 45-second semi-structured verbal protocol consisting of three scripted queries:
    1.  *"Which academic faculty or building did you visit first this morning, and how did you travel to campus?"*
    2.  *"Could you name three distinct landmarks on campus that you passed before reaching the amphitheatre?"*
    3.  *"Briefly describe the current weather and how it feels walking around the lake today."*
*   **Expected Sensory State:**
    *   *Voice:* The participant's vocal responses exceed the 400 RMS threshold and exhibit 300–3000 Hz formant dominance over 3+ of 10 FFT chunks, activating `isSpeaking = true` with a 3-second hangover.
    *   *Gait & Cardiac:* Cadence remains relatively steady, but conversational dual-tasking introduces minor motor fluctuations.
*   **Calculated Cognitive Load:** **Medium**. The base index remains moderate, and the active `isSpeaking` flag triggers the tier escalation rule (`Low` $\rightarrow$ `Medium`).
*   **System Action:**
    *   *Control Group (Static):* Continues unadapted detailed script playback at 1.0× speed with 300 ms pauses. TTS does not duck or pause; participant must divide attention between speaking and listening.
    *   *Experimental Group (Auto):* System maintains detailed content density but **slows speech rate to 0.85×** and **expands inter-line pauses to 1500 ms**. This provides cognitive breathing room and allows auditory processing between vocal utterances without ducking volume.

---

### Phase 3: Tosun Terzioğlu Garden — Dual-Task Motor Load & Overload Stressor
*   **Geofence Coordinates:** $40.89058^\circ\text{ N}, 29.37719^\circ\text{ E}$ (Radius: 80 m).
*   **Physical Path:** Irregular stone-paved garden paths, transitioning to an outdoor stair ascent near the garden boundary.

#### Part 3A: Uneven Terrain & Working Memory Interference (High Cognitive Load)
*   **Stressor Manipulation (Serial 7s Subtraction):** While navigating an uneven cobblestone/stepping-stone garden pathway, the participant is instructed to count backward aloud from 100 by 7s (*"100, 93, 86, 79, 72..."*) continuously.
*   **Expected Sensory State:**
    *   *Gait:* Step-time variability spikes significantly ($> 35$ ms); cadence becomes erratic due to irregular stepping stones and concurrent mental calculation.
    *   *Cardiac:* Aerobic demand increases; mental HR residual rises above the 45-second lag prediction.
    *   *Voice:* Participant speaks numbers periodically, maintaining intermittent voice activity.
*   **Calculated Cognitive Load:** **High** (`combined_load_index` $\ge 50$ with speech bump, or combined index $\ge 100$).
*   **System Action:**
    *   *Control Group (Static):* Continues playing detailed garden script at 1.0× speed and 300 ms pauses.
    *   *Experimental Group (Auto):*
        1.  Content switches to the **Simplified Script** (succinct sentence structures, extraneous clauses removed).
        2.  TTS rate drops to **0.75×**.
        3.  Inter-line pauses expand to **2000 ms**.
        4.  **Checkpoint Repetition Activates:** After speaking any key-fact line (marked in `scripts/tosun_terzioglu_garden_*.txt`), the system re-speaks the core sentence once before advancing, reinforcing crucial cultural-heritage facts directly against working memory decay.

#### Part 3B: Stair Climb Elevation & Acute Overload
*   **Stressor Manipulation (Stair Ascent + Rapid Verification):** The participant ascends a set of outdoor stairs at the garden edge while the researcher asks 3 rapid-fire factual verification questions (*e.g., "Is 15 plus 17 equal to 32?", "Does water freeze at 100 degrees?"*). For participant safety, handrail contact is mandatory, and the researcher walks directly alongside.
*   **Expected Sensory State:**
    *   *Gait & Cardiac:* Rapid cadence transition followed by elevated %HRR strain ($> 40\%$); heart rate exceeds motion prediction.
    *   *Voice:* Continuous rapid speech responses.
*   **Calculated Cognitive Load:** **Overload**. High base load index combined with active voice activity triggers the final tier escalation (`High` $\rightarrow$ `Overload`).
*   **System Action:**
    *   *Control Group (Static):* Narration continues playing uninterrupted regardless of acute physical/mental stress.
    *   *Experimental Group (Auto):*
        1.  **Immediate Audio Pause:** The system halts TTS playback immediately, prioritizing user safety and situational awareness.
        2.  **Overload Earcon:** An attention earcon (`earcon_overload.wav`) chimes to notify the user of the temporary pause.
        3.  **State Retention:** The system caches `lastSpokenText` and marks `pausedByOverload = true`.
*   **Recovery & Auto-Resume with Recap:**
    *   The participant reaches the top landing, stops climbing, and ceases speaking.
    *   After 3.0 seconds, the voice hangover timer expires (`isSpeaking = false`).
    *   Cadence stabilizes, cardiovascular kinetics begin settling, and the calculated load drops back to High/Medium.
    *   The system automatically resumes playback. Operating in eyes-free `AUDIO_FIRST` mode (where the user cannot check a visual transcript), the system leads with a **one-line recap**—re-speaking the last completed script line—before smoothly proceeding with the remaining tour content.

---

## 4. Post-Tour Evaluation & Data Collection Protocol

Immediately upon completion of the final garden script, `TourManager` fires `tourFinishedEvent`. The background recording service flushes telemetry, and the host device presents the fullscreen **Post-Tour Retention Survey Screen** ([RetentionSurveyScreen.kt](file:///home/Alar/Desktop/projects/CAMCSCHAR-demo/app/src/main/kotlin/org/example/RetentionSurveyScreen.kt)).

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       POST-TOUR EVALUATION BATTERY                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ 1. Metadata Verification (Participant ID, Assigned Condition, Timestamp)    │
│ 2. NASA-TLX Subjective Workload Assessment (6 Dimensions, 0-100 Scales)     │
│ 3. Cultural-Heritage Retention Quiz (20 Items, 5 Options A-E)              │
│    ├─ Core Retention Items (12 questions: 4 per POI)                       │
│    ├─ Detailed Retention Items (6 questions: 2 per POI)                     │
│    └─ Attention Trap Checks (2 questions: Q11 & Q18)                       │
│ 4. Objective Telemetry Logging (1 Hz Multi-Sensor JSON Stream)              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.1 NASA-TLX Subjective Workload Assessment
Administered using continuous visual analogue sliders (scored 0–100 in discrete 5-point increments, defaulting to unselected):
1.  **Mental Demand:** *"How much mental and perceptual activity was required (thinking, deciding, calculating, remembering)?"* (0 = Low, 100 = High).
2.  **Physical Demand:** *"How much physical activity was required (walking, climbing stairs, coordinating movement)?"* (0 = Low, 100 = High).
3.  **Temporal Demand:** *"How much time pressure did you feel due to the pace or rate of the tasks or information?"* (0 = Low, 100 = High).
4.  **Performance:** *"How successful do you think you were in accomplishing the goals of the tour (listening, understanding, navigating)?"* (0 = Good, 100 = Poor).
5.  **Effort:** *"How hard did you have to work mentally and physically to accomplish your level of performance?"* (0 = Low, 100 = High).
6.  **Frustration:** *"How irritated, stressed, annoyed, or overwhelmed versus content and relaxed did you feel?"* (0 = Low, 100 = High).

*Composite Metric:* **Mean Workload Score** ($\frac{1}{6} \sum \text{TLX}_i$) is logged alongside individual subscale vectors.

---

### 4.2 Standardized Cultural-Heritage Retention Quiz
The instrument consists of **20 questions** structured across the 3 physical POIs. All items feature 5 choices: 4 plausible factual choices and option **E: "I don't remember / Not sure"**.

#### Question Distribution & Tier Breakdown:
*   **Amphitheatre (Phase 1 — Low Load):**
    *   *Core (Q1–Q4):* Construction year (2003), Project area (5,300 m²), Seating capacity (4,000 spectators), Graduation milestone tradition (unified institutional ceremony).
    *   *Detailed (Q5–Q6):* Anatolian open-air architectural inspiration, Secondary campus use (skateboarding events).
*   **Lake Memorial (Phase 2 — Medium Load):**
    *   *Core (Q7–Q10):* Pre-construction site history (degraded industrial gravel pit), Hydrological refill mechanism (rainwater harvesting & treated wastewater), Wildlife colonization (seagulls transporting fish eggs), Class Brick placement ceremony.
    *   *Detailed (Q12–Q13):* Watertight lining (impermeable clay layered with gravel), Symbolic metaphor of flowing water vs. solid bricks.
    *   *Attention Trap 1 (Q11):* Direct instruction trap: *"To verify that you are reading questions attentively, please select water filtration pumps below."*
*   **Tosun Terzioğlu Garden (Phase 3 — High Load & Overload):**
    *   *Core (Q14–Q17):* Campus plant count (>170,000 plants), Dedication & rector connection (memorized Latin tree nomenclature), Komatsu Sakura sapling donation (2007–2009), Sakura count and variety (nearly 2,000 trees across 7 species).
    *   *Detailed (Q19–Q20):* Sakura Stone Monument origin (Gujyo city area, central Japan), Calligrapher for the "Sakura" kanji (Toshitaka Hagiwara).
    *   *Attention Trap 2 (Q18):* Direct instruction trap: *"To demonstrate attention to survey questions, please select Black Sea rhododendrons below."*

#### Scored Quiz Metrics:
1.  **Core Retention Score:** Points scored out of 12 (4 per POI).
2.  **Detailed Retention Score:** Points scored out of 6 (2 per POI).
3.  **Overall Retention Score:** Total retention points out of 18 (excluding trap checks).
4.  **Omission Rate (%):** Percentage of retention items where Option E (*"Not sure"*) was chosen, indexing perceived recall failure.
5.  **Guessing Error Rate (%):** Percentage of incorrect non-E choices, indexing false recognition or compromised encoding.
6.  **Attention Status:** Scored out of 2. $2/2 = \text{Pass}$ (high fidelity); $1/2 = \text{Borderline}$; $0/2 = \text{Fail}$ (systematic inattention; subject flagged for sensitivity exclusion).
7.  **Response Timings (Logged Silently):** Per-question first-answer latency, last-answer latency, and dwell time (ms) to detect reading fatigue or rushing.

---

### 4.3 Objective Telemetry & Log Artifacts
During the walk, [ExperimentDataManager.kt](file:///home/Alar/Desktop/projects/CAMCSCHAR-demo/app/src/main/kotlin/org/example/ExperimentDataManager.kt) writes synchronized 1 Hz JSON records (`experiment_<timestamp>.json`):
*   **Cardiovascular:** Filtered HR (bpm), rolling RMSSD (ms), Karvonen %HRR strain score (0–100), motion-corrected mental residual (`mental_hr_bpm`).
*   **Locomotor:** Step count, step-time variance (`step_variance`), calculated cadence (steps/min), `isWalking` state.
*   **Attentional:** Voice activity detection boolean (`is_speaking`).
*   **System State:** `combined_load_index`, `load_level`, `is_auto_mode`, `is_detailed_script`, `tts_speed`, `inter_line_delay_ms`, `is_checkpoint_active`, `presentation_mode`.
*   **Spatial:** WGS84 GPS latitude, longitude, and active POI name.

All survey and sensor logs are stored in Android external storage (`/Downloads`) and optionally uploaded via HTTP POST to the local research server configured in the application.

---

## 5. Statistical Analysis & Hypothesis Testing Plan

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         STATISTICAL ANALYSIS PLAN                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ 1. Manipulation Check: Repeated-Measures ANOVA on Cognitive Load Index       │
│    • Validate step-wise increase across Phase 1 -> Phase 2 -> Phase 3       │
│                                                                             │
│ 2. Primary Hypothesis (H1): Mixed Factorial ANOVA (2x3)                     │
│    • Between Factor: Condition (Static vs. Auto)                            │
│    • Within Factor: Phase Load (Amphitheatre, Lake, Garden)                 │
│    • Key Interaction: Condition x Phase on Core Retention                   │
│                                                                             │
│ 3. Overall Retention & Omission: Independent Samples Tests                  │
│    • Overall Retention Score (t-test or Mann-Whitney U)                     │
│    • Omission Rate vs. Guessing Error Rate (Chi-Square / Logistic Reg.)     │
│                                                                             │
│ 4. Subjective Workload (H2): Multivariate Analysis of Variance (MANOVA)    │
│    • NASA-TLX 6 Dimensions + Mean Workload comparison                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.1 Manipulation Check (Stressor Verification)
*   **Analysis:** One-way Repeated-Measures ANOVA across the three tour phases (Phase 1 [Low] vs. Phase 2 [Medium] vs. Phase 3 [High/Overload]) on the mean `combined_load_index` and mean physical strain score.
*   **Success Criterion:** A significant main effect of Phase ($p < .001$) with post-hoc pairwise Bonferroni comparisons confirming monotonic increases ($\text{Phase 1} < \text{Phase 2} < \text{Phase 3}$).

### 5.2 Primary Retention Testing ($H_{1}$)
*   **Overall Difference:** Independent-samples $t$-test (or Mann-Whitney $U$ test if Shapiro-Wilk reveals non-normality) comparing Overall Retention Score (out of 18) between Static ($n=15$) and Auto ($n=15$) groups.
*   **Phase-Specific Interaction Effect:** A **$2 \times 3$ Mixed Factorial ANOVA**:
    *   *Between-Subjects Factor:* Study Condition (Static vs. Auto).
    *   *Within-Subjects Factor:* Phase / Induced Load (Phase 1 [Amphitheatre], Phase 2 [Lake], Phase 3 [Garden]).
    *   *Dependent Variable:* Core Retention Score per phase (out of 4).
    *   *Target Effect:* A significant Condition $\times$ Phase interaction ($p < .05$). Simple main effect tests are hypothesized to show equivalent retention in Phase 1 (ceiling/low stress), with Auto Mode demonstrating significantly superior retention in Phase 3 (high stress) due to simplified phrasing and key-fact checkpoint repetition.

### 5.3 Subjective Workload Testing ($H_{2}$)
*   **Analysis:** Multivariate Analysis of Variance (MANOVA) on the 6 NASA-TLX subscales, followed by independent-samples $t$-tests on individual dimensions and the composite Mean Workload Score.
*   **Expected Results:**
    *   *Mental Demand:* Significantly lower in Auto Mode ($p < .01$, large effect size $d > 0.8$).
    *   *Temporal Demand:* Significantly lower in Auto Mode due to pacing adaptation and 2000 ms pauses ($p < .05$).
    *   *Physical Demand:* No statistically significant difference ($p > .10$), confirming equivalent physical exertion across conditions.

### 5.4 Omission vs. Guessing Analysis ($H_{3}$)
*   **Analysis:** Two-sample test of proportions and independent $t$-tests on the arcsine-transformed Omission Rate (%) and Error Rate (%).
*   **Expected Results:** Participants in Auto Mode will demonstrate significantly fewer omissions on Phase 3 core facts, verifying that checkpoint repetition directly bolstered memory recall rather than inducing random guessing.

---

## 6. Threats to Validity & Rigor Controls

| Threat to Validity | Category | Mitigation & Methodological Control |
|---|---|---|
| **Fact Learning Carryover** | Internal | **Between-Subjects Design:** Each participant experiences the tour and takes the post-test exactly once, eliminating recall practice effects. |
| **TTS Acoustic Confound** | Construct | **Standardized Engine & Pitch:** Fixed Google TTS voice (`en-US`) across all devices; prevents speech clarity or cadence discrepancies from biasing comprehension. |
| **Cross-Modal Visual Interference** | Internal | **Eyes-Free Audio-First Pivot:** The in-tour visual UI is completely disabled in both conditions; participants cannot glance at an on-screen transcript or images. |
| **PPG Motion Artifacts** | Measurement | **HRV De-emphasis & Filtering:** PPG RMSSD is excluded from scoring and relegated to diagnostic logging; cardiac scoring uses filtered HR, Karvonen %HRR, and kinetics-lagged mental residual. |
| **Resting Baseline Fluctuation** | Internal | **Mandatory Pre-Tour Rest:** 2–3 minute quiet seated baseline in Phase 0 ensures self-calibrating resting HR converges before ambulation begins. |
| **Inattentive Survey Responders** | Data Quality | **Embedded Attention Traps:** Two direct-instruction trap items (Q11 & Q18) systematically screen for prompt neglect; fail ($0/2$) cases are excluded. |
| **Participant Safety on Stairs** | Ethical / Safety | **Stair Climb Protocol:** Handrail contact is mandatory during the Phase 3B elevation ascent; the researcher walks directly alongside to manage environmental hazards. |
| **GPS Geofence Drift** | Technical | **Hysteresis Buffers:** 5-meter entry/exit hysteresis applied to POI radii to prevent rapid boundary oscillation. |
