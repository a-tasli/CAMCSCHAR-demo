# Poster Section: Experimental Design and Test Scenario

## 1. Objective and Hypothesis
**Objective:** To evaluate the efficacy of the Context-Aware Multimodal Content Selection (CAMCS) system in dynamically managing auditory and visual Augmented Reality (AR) content based on real-time physiological, visual, and behavioral cognitive load (CL) estimations.
**Hypothesis:** Dynamically adapting output modalities—including TTS parameters (information density, speech rate, pausing, audio ducking) and visual composition (image display, text presentation, map fallback)—based on real-time HR, HRV, gait variance, blink rate, and voice activity will result in higher information retention and lower subjective cognitive overload compared to a static tour.

## 2. Apparatus and Participant Setup
*   **Hardware:** Android smartphone mounted via chest-strap (for accurate accelerometer-based step detection and camera-based blink tracking), Bluetooth Low Energy (BLE) Heart Rate Monitor (e.g., Polar H10) for high-resolution RR intervals. Bone-conduction headphones.
*   **Software:** The CAMCS Android application running locally, actively processing inputs (Gait, HR, Mic, Camera, GPS) into User Context as per the system architecture.
*   **Participants:** $N=30$ healthy adults (ages 18–35).
*   **Conditions (Within-Subjects Design):** 
    1.  *Control (Static Mode):* System plays detailed scripts at 1.0x speed continuously with static visual placeholders, regardless of cognitive load.
    2.  *Experimental (Auto Mode):* System dynamically adjusts script complexity (Information Density), audio gaps, ducking, and Image/Text Composition based on multimodal CL estimation.

## 3. The Test Route & Scenario Walkthrough
The experiment takes place on the Sabancı University campus. GPS Geofencing triggers Cultural Heritage Content when at a POI, and falls back to a Map Display when out of bounds.

### Phase 1: Baseline Calibration & Low Load (Amphitheatre)
*   **Action:** The participant walks along a flat path into the FASS Amphitheatre POI geofence. The app transitions from Map Display to Cultural Heritage Content.
*   **Expected State:** Heart rate is near baseline; gait is regular; microphone detects ambient noise below the 400.0 RMS threshold; blink rate is normal.
*   **System Response:** CL is calculated as **Low**. 
    *   *Audio:* The system plays the *Detailed Script* at normal speed (1.0f) with no delays.
    *   *Visual:* High Information Density. Full text is displayed alongside highly detailed visual AR assets (images).

### Phase 2: Medium Load via Conversation (Lake Memorial)
*   **Action:** The participant approaches the Lake Memorial. The researcher initiates a standardized conversation.
*   **Expected State:** The microphone detects human voice energy. 
*   **System Response:** The Speech Detection node applies a $+1$ Level penalty, pushing CL to **Medium**. 
    *   *Audio (Immediate):* Volume / Audio Ducking triggers (50%) so the user can hear the researcher.
    *   *Audio (Next Index):* TTS Speed slows to 0.75x, and 1.5-second gaps are inserted between sentences.
    *   *Visual:* Image and Text composition remains active.

### Phase 3: High Load via Dual-Task & Environmental Stress (Tosun Terzioğlu Garden)
*   **Action:** The participant navigates uneven terrain in the Garden while performing the N-back task.
*   **Expected State:** SDNN/RMSSD (HRV) indicate stress; step time variance spikes; blink rate increases/becomes erratic; voice activity penalty triggers.
*   **System Response:** CL index crosses into **High**. 
    *   *Audio:* The system seamlessly toggles Information Density to the *Simplified Script*. Speed remains at 0.75x with 1.5s delays.
    *   *Visual:* The system reduces visual clutter, collapsing inline images to a "Show Image" button to minimize divided attention and visual overload.

### Phase 4: Extreme Overload & Auto-Resume
*   **Action:** The participant is asked to rapidly climb a set of stairs near the garden while simultaneously responding to rapid-fire questions.
*   **Expected State:** Gait variance is erratic, HR spikes, eyes squint (low open probability), and voice activity is continuous.
*   **System Response:** The system registers an **Overload** state.
    *   *Audio:* Audio ducks to 50% and TTS **pauses completely**, prioritizing user safety over content.
*   **Recovery Phase:** The participant reaches the top and stops speaking. The debounce timer expires. HR and blink rate recover. The system drops back to High/Medium CL and **automatically resumes** the multimodal tour.

## 4. Data Collection & Evaluation Metrics
### A. Objective System Metrics (Logged continuously)
*   **State Transition Accuracy:** Percentage of time the system successfully identified the induced environmental stressor.
*   **Latency:** Time taken for Audio Ducking to engage upon Speech Detection.
*   **Visual Engagement:** Tracking user interaction with the "Show Image" toggle during High Load states.

### B. Subjective Workload Assessment
*   **NASA-TLX (Task Load Index):** Administered immediately after conditions.
*   *Hypothesis:* NASA-TLX scores for mental/visual demand will be significantly lower in the Auto Mode condition.

### C. Content Retention / Comprehension Test
*   **Post-Tour Quiz:** A 10-question multiple-choice quiz covering key facts.
*   *Hypothesis:* Users in the Auto Mode will score higher on retention during high-load segments because the system dynamically shifted to a Simplified Script and reduced visual clutter, preventing multimodal "inattentional deafness."