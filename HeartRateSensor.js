export class HeartRateSensor {
    constructor() {
        this.device = null;
        this.server = null;
        this.onData = null; // Callback for when data arrives
    }

    async connect() {
        try {
            // Request device with standard BLE Heart Rate service (0x180D)
            this.device = await navigator.bluetooth.requestDevice({
                filters: [{ services: ['heart_rate'] }]
            });

            // Connect to GATT Server
            this.server = await this.device.gatt.connect();

            // Get standard Heart Rate Service
            const service = await this.server.getPrimaryService('heart_rate');

            // Get Heart Rate Measurement Characteristic
            const characteristic = await service.getCharacteristic('heart_rate_measurement');

            // Start listening for heart rate broadcasts
            await characteristic.startNotifications();
            characteristic.addEventListener('characteristicvaluechanged', this.handleCharacteristicValueChanged.bind(this));

            console.log("BLE Heart Rate Sensor Connected");
        } catch (error) {
            console.error("BLE Connection failed: ", error);
            alert("Could not connect to Heart Rate Monitor. Make sure Bluetooth is on and you are using Chrome/Edge.");
        }
    }

    handleCharacteristicValueChanged(event) {
        const value = event.target.value;
        const data = this.parseHeartRate(value);
        if (this.onData) {
            this.onData(data);
        }
    }

    parseHeartRate(value) {
        // Standard BLE Heart Rate Payload parsing
        const flags = value.getUint8(0);
        const rate16Bits = flags & 0x1;
        const rrIntervalPresent = flags & 0x10;

        let index = 1;
        let bpm = rate16Bits ? value.getUint16(index, true) : value.getUint8(index);
        index += rate16Bits ? 2 : 1;

        // Skip Energy Expended if present
        const contactDetected = flags & 0x6;
        const energyPresent = flags & 0x8;
        if (energyPresent) index += 2;

        // Parse RR-Intervals (used for HRV calculation)
        let rrIntervals = [];
        if (rrIntervalPresent) {
            while (index < value.byteLength) {
                // RR intervals are in units of 1/1024 seconds
                const rrValue = value.getUint16(index, true);
                // Convert to milliseconds
                rrIntervals.push((rrValue / 1024.0) * 1000); 
                index += 2;
            }
        }

        // Basic HRV estimation: Root Mean Square of Successive Differences (RMSSD)
        // Note: Optical armbands sometimes estimate RR, so this is an approximation.
        let hrv = 60; // default baseline
        if (rrIntervals.length > 1) {
            let sumSqDiff = 0;
            for (let i = 1; i < rrIntervals.length; i++) {
                let diff = rrIntervals[i] - rrIntervals[i - 1];
                sumSqDiff += diff * diff;
            }
            hrv = Math.sqrt(sumSqDiff / (rrIntervals.length - 1));
        }

        return { bpm, hrv };
    }
}