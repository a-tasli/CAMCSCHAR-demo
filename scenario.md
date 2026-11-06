# Poster Section: Experimental Design and Test Scenario

## 1. Objective and Hypothesis
**Objective:** To evaluate the efficacy of the Context-Aware Content Selection (CAMCS) system in dynamically managing **audio tour delivery** based on real-time physiological and behavioral cognitive load (CL) estimations.

*Context: audio-first pivot (v2).* The system is now an eyes-free, location-aware cultural-heritage **audio guide**; the v1 multimodal visual layer (map, inline images, transcript) is retained only as a legacy flag. All stress inductions from the v1 scenario survive unchanged because none of them were visual.

**Hypothesis:** Dynamically adapting the audio guide's delivery — speech rate, inter-line pausing, information density (detailed vs. simplified scripts), key-fact repetition (checkpoints), and audio ducking — based on real-time HR, gait, and voice activity will result in higher information retention and lower subjective cognitive overload compared to a static audio tour. The single-modality design removes v1's cross-modal confound: any retention difference is attributable to adaptive audio delivery.

## 2. Apparatus and Participant Setup
**Hardware:** Android smartphone (kept in the pocket or screen-down; the screen is used only for the post-tour survey), Bluetooth Low Energy (BLE) heart rate monitor, earphones.

**Software:** The CAMCSCHAR Android application running locally, processing inputs (gait, HR, microphone, GPS) into a real-time cognitive-load estimate that continuously modulates audio delivery (pipeline-spec.md §7). Playback is controlled via a media notification (play/pause/skip); no in-tour UI is required. The TTS engine and voice are fixed across all experimental devices.

**Participants:** 30 healthy adults (ages 18–35).

**A/B Testing:**

a. *Control (Static Mode):* The system plays the detailed script at 1.0× speed continuously, with natural (300 ms) pauses only — no ducking, no density switching, no repetition — regardless of cognitive load. GPS geofencing still triggers the correct POI content.

b. *Experimental (Auto Mode):* The system dynamically adjusts speech rate, inter-line pauses, script density, checkpoint repetition, and ducking based on the cognitive-load estimate and location.

## 3. The Test Route & Scenario Walkthrough
The experiment takes place on the Sabanci University campus; starting at amphitheatre, going through graduation memorial and ending at Tosun Terzioglu Garden. GPS Geofencing triggers Cultural Heritage Content when at a POI.

### Phase 1: Amphitheatre
**Action:** The participant walks along a downhill path into the Amphitheatre POI geofence. An arrival earcon plays, then the POI script begins.

**Expected State:** Heart rate is near baseline; gait is regular; microphone detects ambient noise below the 400.0 RMS threshold.

**System Response:** CL is calculated as **Low**.

*   *Audio:* The detailed script plays at 1.0× with natural inter-line pauses. Scene descriptions are narrated in prose (replacing v1's inline images).

### Phase 2: Lake Memorial

**Action:** The participant approaches the Lake Memorial. The researcher initiates a standardized conversation.

**Expected State:** The microphone detects human-voice energy; conversation adds an attentional demand.

**System Response:** The Speech Detection node applies a level penalty, pushing CL to **Medium**.

*   *Audio:* TTS ducks (≈20 % volume) so the participant can hear and converse. Speech rate slows to 0.85×, and 1.5-second gaps are inserted between sentences to give processing time. The script remains detailed (density switch is reserved for High).

### Phase 3: Tosun Terzioglu Garden

**Action:** The participant navigates uneven terrain in the Garden while counting backwards.

**Expected State:** Step-time variance spikes; HR rises above the motion-predicted baseline; the voice-activity penalty still applies.

**System Response:** CL crosses into **High**.

*   *Audio:* Information density switches to the **simplified script** at 0.75× with 2.0-second gaps, and **checkpoint repetition** activates: after each key fact, the line is repeated once before the tour advances. With no transcript to glance back at, repetition is the audio channel's only repair mechanism.

### Phase 4: Cognitive Overload

**Action:** The participant is asked to climb a set of stairs near the garden while simultaneously responding to rapid-fire questions.

**Expected State:** Gait variance is erratic, HR spikes, and voice activity is continuous.

**System Response:** The system registers an **Overload** state. TTS pauses completely (with an attention tone), prioritizing the user over content.

**Recovery Phase:** The participant reaches the top and stops speaking. The debounce timer expires and HR recovers. The system drops back to High/Medium CL and **automatically resumes with a one-line recap** — the last completed script line is re-spoken — then continues the tour.

## 4. Data Collection & Evaluation Metrics

**State Transition Accuracy:** Percentage of time the system successfully identified the induced environmental stressor.

**NASA-TLX (Task Load Index):** Administered immediately after conditions, on the phone screen.

*   *Hypothesis:* NASA-TLX scores for **mental demand** will be significantly lower in the Auto Mode condition. (The visual-demand subscale is expected to floor in both conditions after the pivot and is retained only for continuity with v1 data.)

**Post-Tour Quiz:** An 18-question multiple-choice quiz covering key facts (12 core facts and 6 detailed bonus questions).

*   *Hypothesis:* Users in Auto Mode will score higher on retention during high-load segments because the system dynamically simplified delivery and repeated key facts while the participant was loaded.
