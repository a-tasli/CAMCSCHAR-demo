package org.example

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Privacy-by-design BLE + Wi-Fi AP environmental context scanner for study phones.
 *
 * - BLE: Uses a 3-tier fallback across the curated Bluetooth SIG catalog (~86 filters):
 *   1) Full continuous filtered scan (relies on AOSP software filtering if MAX_APCF overflows,
 *      retaining screen-off scanning on Android 14/15 because filters are non-empty).
 *   2) Core 16-filter stack if the HAL rejects the full list.
 *   3) Slow hardware-safe 6-filter batch rotation (10 s dwell -> <= 3 starts / 30 s).
 * - Screen-On Recall Probe: One-shot 10 s unfiltered probe (available while screen is on)
 *   to measure [BleRecallProbeResult] without logging any MACs or payloads.
 * - Wi-Fi: 100% SSID-blind access-point scan (ScanResult.SSID / wifiSsid are never read).
 * - Privacy: Raw MACs/BSSIDs are hashed immediately in RAM via [EphemeralIdFilter]
 *   with a 180 s max TTL; device names and manufacturer payload bytes are never read.
 */
@SuppressLint("MissingPermission")
class AmbientScanManager(
    private val context: Context,
    private val scanThrottler: BleScanThrottler = BleScanThrottler()
) {
    companion object {
        private const val TAG = "AmbientScanManager"
        private const val TICK_INTERVAL_MS = 1000L
        private const val WIFI_SCAN_INTERVAL_MS = 20_000L
        private const val BATCH_ROTATION_MS = 10_000L
        private const val RECALL_PROBE_DURATION_MS = 10_000L
        private const val BATCH_SIZE = 6
    }

    private enum class BleScanStrategy(val label: String) {
        FULL("full"),
        CORE_16("core16"),
        BATCH("batch"),
        PROBE("unfiltered_probe"),
        OFF("off")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val idFilter = EphemeralIdFilter()
    private val bleAggregator = BleWindowAggregator()
    private val wifiAggregator = WifiScanAggregator()
    private val catalog: BleFilterCatalog by lazy { BleFilterCatalog.fromAssets(context) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiExecutor = Executors.newSingleThreadExecutor()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isRecallProbeRunning = MutableStateFlow(false)
    val isRecallProbeRunning: StateFlow<Boolean> = _isRecallProbeRunning.asStateFlow()

    private val _snapshot = MutableStateFlow(AmbientContextSnapshot())
    val snapshot: StateFlow<AmbientContextSnapshot> = _snapshot.asStateFlow()

    private var isStarted = false
    private var activeBleCallback: ScanCallback? = null
    private var currentStrategy = BleScanStrategy.OFF
    private var preferredStrategy = BleScanStrategy.FULL
    private var activeFilterCount = 0
    private var batchIndex = 0
    private var lastBatchRotateMs = 0L
    private var lastWifiTriggerMs = 0L

    private var wifiCallbackRegistered = false
    private var wifiBroadcastRegistered = false

    // Latest acoustic values fed from MainActivity ticker (read-only mirror)
    @Volatile private var latestNoiseDbfs: Double? = null
    @Volatile private var latestNoisePeakDbfs: Double? = null
    @Volatile private var latestMidbandRatio: Double? = null
    @Volatile private var latestSpeechDuty10s: Float? = null

    // One-shot recall probe state (RAM-only ephemeral hashes)
    private val probeLock = Any()
    private val probeAllTokens = HashSet<String>()
    private val probeMatchedTokens = HashSet<String>()
    private var latestRecallProbe: BleRecallProbeResult? = null

    private val wifiScanResultsCallback: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                ingestWifiScanResults()
            }
        }
    } else null

    private val wifiBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                ingestWifiScanResults()
            }
        }
    }

    private val periodicTick = object : Runnable {
        override fun run() {
            if (!isStarted || !_isEnabled.value) return
            val now = System.currentTimeMillis()

            if (now - lastWifiTriggerMs >= WIFI_SCAN_INTERVAL_MS) {
                triggerWifiScan(now)
            }

            if (currentStrategy == BleScanStrategy.BATCH &&
                !_isRecallProbeRunning.value &&
                now - lastBatchRotateMs >= BATCH_ROTATION_MS
            ) {
                rotateBleBatch()
            }

            publishSnapshot(now)
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val finishProbeRunnable = Runnable {
        finishRecallProbe()
    }

    fun updateAcousticContext(
        noiseDbfs: Double?,
        noisePeakDbfs: Double?,
        midbandRatio: Double?,
        speechDuty10s: Float?
    ) {
        latestNoiseDbfs = noiseDbfs
        latestNoisePeakDbfs = noisePeakDbfs
        latestMidbandRatio = midbandRatio
        latestSpeechDuty10s = speechDuty10s
        if (!_isEnabled.value) {
            _snapshot.value = AmbientContextSnapshot.disabled(
                ambientNoiseDbfs = noiseDbfs,
                ambientNoisePeakDbfs = noisePeakDbfs,
                ambientMidbandRatio = midbandRatio,
                speechDutyCycle10s = speechDuty10s
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (_isEnabled.value == enabled) return
        _isEnabled.value = enabled
        if (!enabled) {
            stopInternal(clearState = true)
            _snapshot.value = AmbientContextSnapshot.disabled(
                ambientNoiseDbfs = latestNoiseDbfs,
                ambientNoisePeakDbfs = latestNoisePeakDbfs,
                ambientMidbandRatio = latestMidbandRatio,
                speechDutyCycle10s = latestSpeechDuty10s
            )
        } else if (isStarted) {
            idFilter.rotateSalt()
            startInternal()
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        if (_isEnabled.value) {
            startInternal()
        }
    }

    /** Re-evaluates channel permissions after a runtime permission prompt resolves. */
    fun onPermissionsUpdated() {
        if (!isStarted || !_isEnabled.value) return
        if (activeBleCallback == null && hasBleScanPermission()) {
            startBleWithStrategy(preferredStrategy)
        }
        registerWifiListener()
        if (wifiAggregator.currentSummary() == null && hasWifiScanPermission()) {
            triggerWifiScan(System.currentTimeMillis())
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        stopInternal(clearState = true)
    }

    fun destroy() {
        stop()
        wifiExecutor.shutdown()
    }

    /**
     * Runs a 10-second unfiltered BLE scan (screen-on only) to empirically validate
     * what fraction of nearby BLE advertisers match [BleFilterCatalog].
     */
    fun runScreenOnRecallProbe(): Boolean {
        if (!_isEnabled.value || _isRecallProbeRunning.value) return false
        if (powerManager?.isInteractive != true) {
            Log.w(TAG, "Recall probe requires interactive screen-on state")
            return false
        }
        if (!hasBleScanPermission()) return false
        val scanner = bluetoothAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        if (!scanThrottler.tryAcquire()) {
            Log.w(TAG, "Recall probe deferred by BleScanThrottler")
            return false
        }

        stopBleScanOnly()
        synchronized(probeLock) {
            probeAllTokens.clear()
            probeMatchedTokens.clear()
        }
        _isRecallProbeRunning.value = true
        currentStrategy = BleScanStrategy.PROBE
        activeFilterCount = 0

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = createBleCallback(isProbe = true)
        activeBleCallback = callback
        try {
            scanner.startScan(emptyList(), settings, callback)
            mainHandler.removeCallbacks(finishProbeRunnable)
            mainHandler.postDelayed(finishProbeRunnable, RECALL_PROBE_DURATION_MS)
            publishSnapshot(System.currentTimeMillis())
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recall probe: ${e.message}")
            _isRecallProbeRunning.value = false
            startBleWithStrategy(preferredStrategy)
            return false
        }
    }

    private fun finishRecallProbe() {
        if (!_isRecallProbeRunning.value) return
        stopBleScanOnly()
        _isRecallProbeRunning.value = false

        val total: Int
        val matched: Int
        synchronized(probeLock) {
            total = probeAllTokens.size
            matched = probeMatchedTokens.size
            probeAllTokens.clear()
            probeMatchedTokens.clear()
        }
        val ratio = if (total > 0) matched.toDouble() / total else null
        latestRecallProbe = BleRecallProbeResult(
            probeTotalCount = total,
            probeMatchedCount = matched,
            probeCoverageRatio = ratio,
            probeTimestampMs = System.currentTimeMillis()
        )

        if (isStarted && _isEnabled.value) {
            startBleWithStrategy(preferredStrategy)
        }
        publishSnapshot(System.currentTimeMillis())
    }

    private fun startInternal() {
        registerWifiListener()
        triggerWifiScan(System.currentTimeMillis())
        startBleWithStrategy(preferredStrategy)
        mainHandler.removeCallbacks(periodicTick)
        mainHandler.postDelayed(periodicTick, TICK_INTERVAL_MS)
    }

    private fun stopInternal(clearState: Boolean) {
        mainHandler.removeCallbacks(periodicTick)
        mainHandler.removeCallbacks(finishProbeRunnable)
        _isRecallProbeRunning.value = false
        stopBleScanOnly()
        unregisterWifiListener()
        if (clearState) {
            bleAggregator.clear()
            wifiAggregator.clear()
            synchronized(probeLock) {
                probeAllTokens.clear()
                probeMatchedTokens.clear()
            }
            idFilter.clear()
            currentStrategy = BleScanStrategy.OFF
            activeFilterCount = 0
        }
    }

    private fun startBleWithStrategy(strategy: BleScanStrategy) {
        if (!hasBleScanPermission()) {
            currentStrategy = BleScanStrategy.OFF
            activeFilterCount = 0
            return
        }
        val scanner: BluetoothLeScanner = bluetoothAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            currentStrategy = BleScanStrategy.OFF
            activeFilterCount = 0
            return
        }

        if (!scanThrottler.tryAcquire()) {
            val waitMs = scanThrottler.msUntilNextSlot().coerceAtLeast(1000L)
            mainHandler.postDelayed({
                if (isStarted && _isEnabled.value && !_isRecallProbeRunning.value && activeBleCallback == null) {
                    startBleWithStrategy(strategy)
                }
            }, waitMs)
            return
        }

        stopBleScanOnly()

        val specs: List<BleFilterSpec> = when (strategy) {
            BleScanStrategy.FULL -> catalog.allSpecs
            BleScanStrategy.CORE_16 -> catalog.coreSpecs
            BleScanStrategy.BATCH -> {
                val batches = catalog.batchedSpecs(BATCH_SIZE)
                if (batches.isEmpty()) emptyList() else batches[batchIndex % batches.size]
            }
            BleScanStrategy.PROBE, BleScanStrategy.OFF -> emptyList()
        }

        val scanFilters = buildAndroidScanFilters(specs)
        if (scanFilters.isEmpty()) {
            currentStrategy = BleScanStrategy.OFF
            activeFilterCount = 0
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        val callback = createBleCallback(isProbe = false)
        activeBleCallback = callback
        currentStrategy = strategy
        activeFilterCount = scanFilters.size
        if (strategy == BleScanStrategy.BATCH) {
            lastBatchRotateMs = System.currentTimeMillis()
        }

        try {
            scanner.startScan(scanFilters, settings, callback)
        } catch (e: Exception) {
            Log.w(TAG, "BLE startScan threw under strategy ${strategy.label}: ${e.message}")
            activeBleCallback = null
            fallbackFromFailedStrategy(strategy)
        }
    }

    private fun rotateBleBatch() {
        val batches = catalog.batchedSpecs(BATCH_SIZE)
        if (batches.size <= 1) return
        batchIndex = (batchIndex + 1) % batches.size
        startBleWithStrategy(BleScanStrategy.BATCH)
    }

    private fun fallbackFromFailedStrategy(failed: BleScanStrategy) {
        val next = when (failed) {
            BleScanStrategy.FULL -> BleScanStrategy.CORE_16
            BleScanStrategy.CORE_16 -> BleScanStrategy.BATCH
            BleScanStrategy.BATCH, BleScanStrategy.PROBE, BleScanStrategy.OFF -> null
        }
        if (next != null && isStarted && _isEnabled.value) {
            preferredStrategy = next
            Log.i(TAG, "Falling back BLE scan strategy from ${failed.label} to ${next.label}")
            startBleWithStrategy(next)
        } else {
            currentStrategy = BleScanStrategy.OFF
            activeFilterCount = 0
        }
    }

    private fun stopBleScanOnly() {
        val cb = activeBleCallback ?: return
        activeBleCallback = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
    }

    private fun createBleCallback(isProbe: Boolean): ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result != null) handleBleResult(result, isProbe)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleBleResult(it, isProbe) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed (code=$errorCode) under ${currentStrategy.label}")
            activeBleCallback = null
            if (isProbe) {
                _isRecallProbeRunning.value = false
                if (isStarted && _isEnabled.value) startBleWithStrategy(preferredStrategy)
            } else {
                fallbackFromFailedStrategy(currentStrategy)
            }
        }
    }

    private fun handleBleResult(result: ScanResult, isProbe: Boolean) {
        val rawMac = result.device?.address ?: return
        // Privacy boundary: convert MAC to volatile salted hash immediately; never read device.name or payload bytes
        val token = idFilter.hash(rawMac)

        val record = result.scanRecord
        val companyIds = ArrayList<Int>(2)
        val mfgData = record?.manufacturerSpecificData
        if (mfgData != null) {
            for (i in 0 until mfgData.size()) {
                companyIds.add(mfgData.keyAt(i))
            }
        }
        val serviceUuids = LinkedHashSet<String>()
        record?.serviceUuids?.forEach { serviceUuids.add(it.uuid.toString().uppercase()) }
        // AD Type 0x16 keys (Fast Pair, Eddystone, SmartTag, Tile, Exposure) — read UUID keys only, never payload bytes
        record?.serviceData?.keys?.forEach { serviceUuids.add(it.uuid.toString().uppercase()) }

        val category = catalog.classify(companyIds, serviceUuids.toList())
        if (isProbe) {
            synchronized(probeLock) {
                probeAllTokens.add(token)
                if (category != null) {
                    probeMatchedTokens.add(token)
                }
            }
        }
        bleAggregator.recordObservation(
            ephemeralToken = token,
            rssi = result.rssi,
            category = category ?: BleDeviceCategory.OTHER,
            nowMs = System.currentTimeMillis()
        )
    }

    private fun buildAndroidScanFilters(specs: List<BleFilterSpec>): List<ScanFilter> {
        val zeroByte = byteArrayOf(0x00)
        return specs.map { spec ->
            when (spec) {
                is BleFilterSpec.Manufacturer -> ScanFilter.Builder()
                    // 1-byte zeroed mask satisfies Android 14/15 non-empty filter checks while matching any payload >= 1 byte
                    .setManufacturerData(spec.entry.companyId, zeroByte, zeroByte)
                    .build()
                is BleFilterSpec.ServiceUuid -> ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(spec.entry.fullUuidString))
                    .build()
                is BleFilterSpec.ServiceData -> ScanFilter.Builder()
                    // Matches AD Type 0x16 Service Data (e.g., Fast Pair 0xFE2C, Eddystone 0xFEAA, SmartTag 0xFD5A/FD69)
                    .setServiceData(ParcelUuid.fromString(spec.entry.fullUuidString), zeroByte, zeroByte)
                    .build()
            }
        }
    }

    private fun registerWifiListener() {
        val wm = wifiManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!wifiCallbackRegistered) {
                try {
                    val cb = wifiScanResultsCallback as WifiManager.ScanResultsCallback
                    wm.registerScanResultsCallback(wifiExecutor, cb)
                    wifiCallbackRegistered = true
                } catch (e: Exception) {
                    Log.w(TAG, "registerScanResultsCallback failed: ${e.message}")
                }
            }
        } else if (!wifiBroadcastRegistered) {
            try {
                context.registerReceiver(
                    wifiBroadcastReceiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                )
                wifiBroadcastRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "registerReceiver for Wi-Fi scan failed: ${e.message}")
            }
        }
    }

    private fun unregisterWifiListener() {
        val wm = wifiManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifiCallbackRegistered) {
            try {
                val cb = wifiScanResultsCallback as WifiManager.ScanResultsCallback
                wm.unregisterScanResultsCallback(cb)
            } catch (_: Exception) {
            }
            wifiCallbackRegistered = false
        }
        if (wifiBroadcastRegistered) {
            try {
                context.unregisterReceiver(wifiBroadcastReceiver)
            } catch (_: Exception) {
            }
            wifiBroadcastRegistered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun triggerWifiScan(nowMs: Long) {
        lastWifiTriggerMs = nowMs
        if (!hasWifiScanPermission()) return
        val wm = wifiManager ?: return
        try {
            wm.startScan()
        } catch (e: Exception) {
            Log.w(TAG, "wifiManager.startScan failed: ${e.message}")
        }
        // Also ingest latest cached OS scan results immediately if none came recently
        if (wifiAggregator.currentSummary() == null) {
            ingestWifiScanResults()
        }
    }

    private fun ingestWifiScanResults() {
        if (!_isEnabled.value || !hasWifiScanPermission()) return
        val wm = wifiManager ?: return
        val rawResults = try {
            wm.scanResults
        } catch (e: Exception) {
            Log.w(TAG, "Reading wifi scanResults failed: ${e.message}")
            return
        } ?: return

        // 100% SSID-blind: never read result.SSID or result.wifiSsid
        val observations = rawResults.mapNotNull { sr ->
            val bssid = sr.BSSID ?: return@mapNotNull null
            val isMc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) sr.is80211mcResponder else false
            WifiApObservation(
                ephemeralBssidToken = idFilter.hash(bssid),
                rssi = sr.level,
                frequencyMhz = sr.frequency,
                isLocallyAdministered = WifiScanAggregator.isLocallyAdministeredMac(bssid),
                is80211mcResponder = isMc
            )
        }
        wifiAggregator.onScanResults(observations, System.currentTimeMillis())
    }

    private fun publishSnapshot(nowMs: Long) {
        if (!_isEnabled.value) return
        val ble = bleAggregator.summarize(nowMs)
        val wifi = wifiAggregator.currentSummary()
        val scanAgeMs = wifi?.let { (nowMs - it.lastScanMs).coerceAtLeast(0L) }

        _snapshot.value = AmbientContextSnapshot(
            bleTotalCount = ble.totalCount,
            bleNearCount = ble.nearCount,
            bleMidCount = ble.midCount,
            bleFarCount = ble.farCount,
            bleMeanRssi = ble.meanRssi,
            bleMaxRssi = ble.maxRssi,
            bleTurnoverRate = ble.turnoverRate,
            blePersistentCount = ble.persistentCount,
            blePacketRateHz = ble.packetRateHz,
            blePhoneEcosystemCount = ble.phoneEcosystemCount,
            bleAudioWearableCount = ble.audioWearableCount,
            bleTrackerBeaconCount = ble.trackerBeaconCount,
            bleChipsetOtherCount = ble.chipsetOtherCount,
            bleFilterMode = currentStrategy.label,
            bleFilterCount = activeFilterCount,
            bleRecallProbe = latestRecallProbe,

            wifiApCount = wifi?.apCount,
            wifiStrongApCount = wifi?.strongApCount,
            wifi5GhzRatio = wifi?.ratio5Ghz,
            wifiMeanRssi = wifi?.meanRssi,
            wifiRssiStd = wifi?.rssiStd,
            wifiRssiDeltaAbsMean = wifi?.rssiDeltaAbsMean,
            wifiLaaBssidCount = wifi?.laaBssidCount,
            wifiTransientApCount = wifi?.transientApCount,
            wifiRttApCount = wifi?.rttApCount,
            wifiScanAgeMs = scanAgeMs,

            ambientNoiseDbfs = latestNoiseDbfs,
            ambientNoisePeakDbfs = latestNoisePeakDbfs,
            ambientMidbandRatio = latestMidbandRatio,
            speechDutyCycle10s = latestSpeechDuty10s,

            envEnclosureHint = AmbientContextClassifier.inferEnclosureHint(wifi),
            envBusynessTier = AmbientContextClassifier.inferBusynessTier(ble, latestNoiseDbfs, wifi),
            isAmbientSensingEnabled = true
        )
    }

    private fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasWifiScanPermission(): Boolean {
        val hasAccessWifi = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasAccessWifi) return false

        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasLocation || ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            hasLocation
        }
    }
}
