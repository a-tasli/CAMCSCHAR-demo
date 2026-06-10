# Poster Section: Experimental Design and Test Scenario

## 1. Objective and Hypothesis
**Objective:** To evaluate the efficacy of the Context-Aware Multimodal Content Selection (CAMCS) system in dynamically managing auditory and visual Augmented Reality (AR) content based on real-time physiological, visual, and behavioral cognitive load (CL) estimations.

**Hypothesis:** Dynamically adapting output modalities—including TTS parameters (information density, speech rate, pausing, audio ducking) and visual composition (image display, text presentation, map fallback)—based on real-time HR, HRV, gait variance, and voice activity will result in higher information retention and lower subjective cognitive overload compared to a static tour.

## 2. Apparatus and Participant Setup
**Hardware:** Android smartphone, Bluetooth Low Energy (BLE) heart rate monitor, earphones.

**Software:** The CAMCSCHAR Android application running locally, actively processing inputs (gait, HR, mic, GPS) into user context as per the system architecture.

**Participants:** 30 healthy adults (ages 18–35).

**A/B Testing:**

a. *Control (Static Mode):* System plays detailed scripts at 1.0x speed continuously with static text, images, and map display, regardless of cognitive load or GPS location.

b. *Experimental (Auto Mode):* System dynamically adjusts script complexity (information density), audio gaps, ducking, map display, and image/text composition based on multimodal CL estimation and location.

## 3. The Test Route & Scenario Walkthrough
The experiment takes place on the Sabanci University campus; starting at amphitheatre, going through graduation memorial and ending at Tosun Terzioglu Garden. GPS Geofencing triggers Cultural Heritage Content when at a POI.

### Phase 1: Amphitheatre
**Action:** The participant walks along a downhill path into the Amphitheatre POI geofence. The app transitions from Map Display to Cultural Heritage Content.

**Expected State:** Heart rate is near baseline; gait is regular; microphone detects ambient noise below the 400.0 RMS threshold; blink rate is normal.

**System Response:** CL is calculated as **Low**.

*   *Audio:* The system plays the detailed script at normal speed with no delays.
*   *Visual:* High information density. Full text is displayed alongside images integrated into text.

### Phase 2: Lake Memorial

**Action:** The participant approaches the Lake Memorial. The researcher initiates a standardized conversation.

**Expected State:** The microphone detects human voice energy. 

**System Response:** The Speech Detection node applies a level penalty, along with increased load from conversation, pushing CL to **Medium**.

*   *Audio:* Audio Ducking triggers (50%) so the user can hear the researcher. TTS Speed slows to 0.75x, and 1.5-second gaps are inserted between sentences.
*   *Visual:* The system reduces visual clutter, collapsing inline images to a "Show Image" button to minimize divided attention and visual overload.

### Phase 3: Tosun Terzioglu Garden

**Action:** The participant navigates uneven terrain in the Garden while counting backwards.

**Expected State:** SDNN/RMSSD (HRV) indicate stress; step time variance spikes; voice activity penalty triggers.

**System Response:** CL index crosses into **High**.

*   *Audio:* The system toggles Information Density to the simplified script. Speed remains at 0.75x with 1.5s delays.
*   *Visual:* Same with Medium Load, except text sentences are also simplified to match the script.

### Phase 4: Cognitive Overload

**Action:** The participant is asked to climb a set of stairs near the garden while simultaneously responding to rapid-fire questions.

**Expected State:** Gait variance is erratic, HR spikes, and voice activity is continuous.

**System Response:** The system registers an **Overload** state, aiming to reduce Cognitive Load and user stress.

*   *Audio:* Audio ducks to 50% and TTS pauses completely, prioritizing the user over content.
*   *Visual:* Content display pauses.

**Recovery Phase:** The participant reaches the top and stops speaking. The debounce timer expires. HRV recovers. The system drops back to High/Medium CL and automatically resumes the multimodal tour.

## 4. Data Collection & Evaluation Metrics

**State Transition Accuracy:** Percentage of time the system successfully identified the induced environmental stressor.

**NASA-TLX (Task Load Index):** Administered immediately after conditions.

*   *Hypothesis:* NASA-TLX scores for mental/visual demand will be significantly lower in the Auto Mode condition.

**Post-Tour Quiz:** A 10-question multiple-choice quiz covering key facts.

*   *Hypothesis:* Users in the Auto Mode will score higher on retention during high-load segments because the system dynamically shifted to a simplified script and reduced visual (and audio) clutter.
