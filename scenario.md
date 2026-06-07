# Poster Section: Experimental Design and Test Scenario

## 1. Objective and Hypothesis
**Objective:** To evaluate the efficacy of the Context-Aware Multimodal Content Selection (CAMCS) system in dynamically managing auditory Augmented Reality (AR) content based on real-time physiological and behavioral cognitive load (CL) estimations.
**Hypothesis:** Dynamically adapting TTS parameters (script complexity, speech rate, pausing, and audio ducking) based on real-time HR, HRV, gait variability, and voice activity will result in higher information retention and lower subjective cognitive overload compared to a static audio tour.

## 2. Apparatus and Participant Setup
*   **Hardware:** Android smartphone strapped securely to the chest (for accurate accelerometer-based step detection and vertical axis gait analysis), Bluetooth Low Energy (BLE) chest-strap Heart Rate Monitor (e.g., Polar H10) for high-resolution RR intervals. Bone-conduction headphones (to maintain environmental awareness).
*   **Software:** The CAMCS Android application running in the background.
*   **Participants:** $N=30$ healthy adults (ages 18–35).
*   **Conditions (Within-Subjects Design):** 
    1.  *Control (Static Mode):* System plays detailed scripts at 1.0x speed continuously, regardless of cognitive load.
    2.  *Experimental (Auto Mode):* System dynamically adjusts script complexity, speed, pauses, and volume based on multimodal CL estimation.

## 3. The Test Route & Scenario Walkthrough
The experiment takes place on the Sabancı University campus, utilizing the three authored locations. The route is specifically designed to induce varying levels of cognitive and physical load.

### Phase 1: Baseline Calibration & Low Load (Amphitheatre)
*   **Action:** The participant walks along a flat, paved, uncrowded path toward the FASS Amphitheatre.
*   **Expected State:** Heart rate is near resting baseline; gait is highly regular (low variability); microphone detects ambient noise below the 400.0 RMS threshold.
*   **System Response:** CL is calculated as **Low**. The system plays the *Amphitheatre Detailed Script* at normal speed (1.0f) with no delays between sentences.

### Phase 2: Medium Load via Conversation (Lake Memorial)
*   **Action:** The participant approaches the Lake Memorial. The researcher initiates a standardized conversation (e.g., "Can you describe the architecture of the building to your left?").
*   **Expected State:** The microphone detects human voice energy (FFT voice band > 1.5x noise band). 
*   **System Response:** The system applies a $+1$ Level penalty, pushing the CL to **Medium**. 
    *   *Immediate Action:* Audio volume ducks to 50% so the user can hear themselves and the researcher.
    *   *Next Index Action:* TTS speed slows to 0.75x, and 1.5-second delays are inserted between sentences to reduce auditory clutter while maintaining the Detailed Script.

### Phase 3: High Load via Dual-Task & Environmental Stress (Tosun Terzioğlu Garden)
*   **Action:** The participant navigates the Tosun Terzioğlu Garden. They are instructed to walk across uneven terrain (grass/gravel) while performing a classic cognitive stressor: the N-back task or counting backwards from 100 by 7s aloud.
*   **Expected State:** SDNN/RMSSD (HRV) indicate stress; heart rate elevates; step time variability spikes due to uneven terrain and cognitive distraction. Voice activity penalty triggers.
*   **System Response:** CL index crosses the >100 threshold into **High** (or pushes to Overload if voice is sustained). 
    *   *Next Index Action:* The system seamlessly toggles to the *Tosun Terzioğlu Garden Simplified Script*. Speed remains at 0.75x with 1.5s delays, ensuring the user only receives the most critical, digestible information.

### Phase 4: Extreme Overload & Auto-Resume
*   **Action:** The participant is asked to rapidly climb a set of stairs near the garden while simultaneously responding to rapid-fire questions from the researcher.
*   **Expected State:** Gait variability is erratic, HR spikes significantly above baseline, and voice activity is continuous.
*   **System Response:** The system registers an **Overload** state.
    *   *Immediate Action:* Audio ducks to 50%.
    *   *Next Index Action:* The TTS **pauses completely**, prioritizing user safety and environmental awareness over content delivery.
*   **Recovery Phase:** The participant reaches the top of the stairs and stops speaking. The 3-second debounce timer expires, removing the voice penalty. HR begins to recover. The system registers a drop back to High/Medium CL and **automatically resumes** the tour from the exact index it paused at.

## 4. Data Collection & Evaluation Metrics
To rigorously prove the system's effectiveness for the ISMAR poster, the following metrics will be logged and analyzed:

### A. Objective System Metrics (Logged continuously via the app)
*   **State Transition Accuracy:** Percentage of time the system successfully identified the induced environmental stressor and switched states.
*   **Latency:** Time taken for the audio ducking to engage upon voice detection (should be < 128ms based on FFT buffer size).

### B. Subjective Workload Assessment
*   **NASA-TLX (Task Load Index):** Administered immediately after the Control and Experimental conditions to measure perceived mental demand, physical demand, and frustration.
*   *Hypothesis for Poster:* The NASA-TLX scores for auditory/mental demand will be significantly lower in the Experimental (Auto Mode) condition.

### C. Content Retention / Comprehension Test
*   **Post-Tour Quiz:** A 10-question multiple-choice quiz covering key facts from the three locations (e.g., "How old is the Sakura stone?", "What is the function of the living stairs?").
*   *Hypothesis for Poster:* Users in the Experimental condition will score higher on retention during the high-load (Garden/Lake) segments because the system dynamically shifted to the Simplified script, avoiding the "inattentional deafness" that occurs when complex audio is played during high cognitive load.