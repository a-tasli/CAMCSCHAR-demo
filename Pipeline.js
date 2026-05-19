export class Pipeline {
    constructor() {
        this.cognitiveStrain = "Low";
        this.lastUpdate = 0;
    }

    // 1. Data Acquisition: Polling ocular, physiological, kinematic, tactile, and audio data.
    acquireData(gazeData) {
        // In a real scenario, this would poll actual hardware/sensors.
        // For this demo, we use the real ocular data (gaze) and simulate the others.
        return {
            ocular: gazeData,
            physiological: { hrv: Math.random() * 20 + 60 }, // Mock Heart Rate Variability
            kinematic: { mobility: Math.random() > 0.8 ? "Walking" : "Static" }, // Mock mobility
            tactile: { touchFrequency: Math.random() },
            audio: { voiceActivity: Math.random() } // Mock microphone input
        };
    }

    // 2. Feature Extraction: Voice activity (Wav2Vec2), heart rate variability, ocular metrics, mobility states & gait variety, Kalman filter.
    extractFeatures(rawData) {
        // Simulate feature extraction from the raw data arrays
        
        // Ocular metric: Distance from center screen (simulating cognitive strain indicator)
        const centerX = window.innerWidth / 2;
        const centerY = window.innerHeight / 2;
        const ocularMetric = rawData.ocular ? 
            Math.sqrt(Math.pow(rawData.ocular.x - centerX, 2) + Math.pow(rawData.ocular.y - centerY, 2)) : 0;
            
        return {
            gazeDeviation: ocularMetric,
            hrv: rawData.physiological.hrv,
            mobilityState: rawData.kinematic.mobility,
            voiceActivityLevel: rawData.audio.voiceActivity
        };
    }

    // 3. Logic Engine: Dynamic Fuzzy Logic Controller applying linguistic rules
    logicEngine(features) {
        // Simulated Fuzzy Logic Controller rules determining cognitive strain
        let strainScore = 0;
        
        // Rule 1: High gaze deviation might indicate searching/confusion/distraction
        if (features.gazeDeviation > window.innerWidth / 3) strainScore += 1.5;
        
        // Rule 2: High voice activity might indicate talking or external distraction
        if (features.voiceActivityLevel > 0.7) strainScore += 1.0;
        
        // Rule 3: Moving while using the AR app significantly increases cognitive load
        if (features.mobilityState === "Walking") strainScore += 1.5;

        // Linguistic Variable Mapping
        if (strainScore >= 2.5) return "High";
        if (strainScore >= 1.0) return "Medium";
        return "Low";
    }

    // 4. Context-Aware Presentation: Adaptive modality switching (Audio vs. Visual), Adjusting information density.
    updatePresentation(strainLevel, uiElement) {
        // Throttle updates to avoid flickering UI in the demo
        if (this.lastUpdate && (Date.now() - this.lastUpdate < 1000)) return;
        this.lastUpdate = Date.now();
        
        this.cognitiveStrain = strainLevel;

        const title = uiElement.querySelector('h2');
        const content = uiElement.querySelector('p');

        // Adapt UI based on current cognitive strain limits
        if (strainLevel === "High") {
            uiElement.className = 'presentation-panel strain-high';
            title.innerText = "Cognitive Strain: HIGH";
            content.innerText = "Modality Switch: Audio-only mode activated. Visual details minimized to prevent overload.";
        } else if (strainLevel === "Medium") {
            uiElement.className = 'presentation-panel strain-medium';
            title.innerText = "Cognitive Strain: MEDIUM";
            content.innerText = "Adjusting Density: Simplified visual layout. Presenting only key bullet points.";
        } else {
            uiElement.className = 'presentation-panel strain-low';
            title.innerText = "Cognitive Strain: LOW";
            content.innerText = "Rich Multimodal Format: Full 3D model interaction, detailed historical text, and ambient audio active.";
        }
    }

    // Main pipeline execution flow
    process(gazeData, uiElement) {
        const rawData = this.acquireData(gazeData);
        const features = this.extractFeatures(rawData);
        const strainLevel = this.logicEngine(features);
        this.updatePresentation(strainLevel, uiElement);
    }
}