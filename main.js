import { EyeTracker } from './EyeTracker.js';
import { Pipeline } from './Pipeline.js';

const dot = document.getElementById('gaze-dot');
const status = document.getElementById('status-display');
const startBtn = document.getElementById('start-btn');
const stopBtn = document.getElementById('stop-btn');
const calibOverlay = document.getElementById('calibration-overlay');
const presentationUI = document.getElementById('presentation-ui');

const tracker = new EyeTracker();
const pipeline = new Pipeline();
let isCalibrated = false;

// Callback to handle gaze data
const handleGaze = (x, y) => {
    // Only show the moving dot AFTER calibration is done
    if (!isCalibrated) return; 

    dot.style.display = 'block';
    dot.style.left = `${x}px`;
    dot.style.top = `${y}px`;

    // Run the content selection pipeline
    pipeline.process({x, y}, presentationUI);
};

// --- NEW: Calibration Logic ---
function runCalibration(onComplete) {
    calibOverlay.style.display = 'block';
    status.innerText = "Calibrating...";

    // 9 points across the screen (percentages for responsive layout)
    const positions = [
        [10, 10], [50, 10], [90, 10],
        [10, 50], [50, 50], [90, 50],
        [10, 90], [50, 90], [90, 90]
    ];

    let pointsRemaining = positions.length;

    positions.forEach(pos => {
        const cDot = document.createElement('div');
        cDot.className = 'calib-dot';
        cDot.style.left = pos[0] + 'vw';
        cDot.style.top = pos[1] + 'vh';
        
        // NEW: Hide video feed when hovering over ANY dot
        cDot.addEventListener('mouseenter', () => {
            const video = document.getElementById('webgazerVideoContainer');
            if (video) video.style.opacity = '0'; // Completely hide the video
        });

        // NEW: Bring video feed back when mouse leaves the dot
        cDot.addEventListener('mouseleave', () => {
            const video = document.getElementById('webgazerVideoContainer');
            if (video) video.style.opacity = '1'; // Bring it back
        });
        
        cDot.addEventListener('click', () => {
            cDot.style.display = 'none'; 
            pointsRemaining--;
            
            // Ensure video comes back permanently if it was the last dot
            const video = document.getElementById('webgazerVideoContainer');
            if (video) video.style.opacity = '1'; 

            if (pointsRemaining === 0) {
                calibOverlay.style.display = 'none';
                isCalibrated = true;
                if (presentationUI) presentationUI.style.display = 'block';
                onComplete();
            }
        });
        calibOverlay.appendChild(cDot);
    });
}

// --- Event Listeners ---
startBtn.addEventListener('click', async () => {
    status.innerText = "Warming up camera...";
    startBtn.disabled = true;
    
    // 1. Initialize the camera
    await tracker.init(handleGaze);
    tracker.start();

    // 2. Run the calibration screen
    runCalibration(() => {
        status.innerText = "Tracking Active! Look around the screen.";
        stopBtn.disabled = false;
    });
});

stopBtn.addEventListener('click', () => {
    tracker.stop();
    dot.style.display = 'none';
    isCalibrated = false; // Reset calibration state
    if (presentationUI) presentationUI.style.display = 'none'; // Hide presentation
    
    // Clean up old calibration dots if we want to restart
    document.querySelectorAll('.calib-dot').forEach(el => el.remove());

    status.innerText = "Tracking Paused.";
    startBtn.disabled = false;
    stopBtn.disabled = true;
});