export class EyeTracker {
    constructor() {
        this.webgazer = window.webgazer;
        this.isTracking = false;
    }

    async init(onGazeUpdate) {
        // Configure WebGazer
        await this.webgazer.setGazeListener((data, elapsedTime) => {
            if (data == null) return;
            onGazeUpdate(data.x, data.y);
        }).begin();

        // Hide the default WebGazer video feed/dot to use our own custom UI
        this.webgazer.showVideoPreview(true)
            .showPredictionPoints(false)
            .applyKalmanFilter(true); // Smooths out the jitter
    }

    start() {
        this.webgazer.resume();
        this.isTracking = true;
    }

    stop() {
        this.webgazer.pause();
        this.isTracking = false;
    }
}
