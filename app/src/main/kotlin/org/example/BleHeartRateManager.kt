package org.example

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleHeartRateManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate

    private val _rrIntervals = MutableStateFlow<List<Int>>(emptyList())
    val rrIntervals: StateFlow<List<Int>> = _rrIntervals

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    companion object {
        private const val TAG = "BleHeartRateManager"
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                Log.d(TAG, "Found device: ${device.name ?: device.address}")
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _connectionState.value = "Scan Failed ($errorCode)"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = "Connected. Discovering services..."
                    Log.d(TAG, "Connected to GATT server. Attempting to start service discovery")
                    // A delay is sometimes needed after connection before discovering services
                    Handler(Looper.getMainLooper()).postDelayed({
                        gatt.discoverServices()
                    }, 600)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = "Disconnected"
                    Log.d(TAG, "Disconnected from GATT server.")
                    closeGatt()
                    // Auto-reconnect or restart scan could be added here
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(HEART_RATE_SERVICE_UUID)
                if (service != null) {
                    val characteristic = service.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        if (descriptor != null) {
                            // On Android 13+ (API 33), there is a newer method for writeDescriptor, but we use the deprecated one for backward compatibility or standard one.
                            // To be safe and avoid deprecation warnings crashing compilation, we use standard approach.
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                            _connectionState.value = "Monitoring Heart Rate"
                        } else {
                            _connectionState.value = "Error: Descriptor not found"
                        }
                    } else {
                        _connectionState.value = "Error: Characteristic not found"
                    }
                } else {
                    _connectionState.value = "Error: HR Service not found"
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
                _connectionState.value = "Service Discovery Failed"
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val hrValue = extractHeartRate(characteristic)
                _heartRate.value = hrValue
                
                val rrValues = extractRRIntervals(characteristic)
                if (rrValues.isNotEmpty()) {
                    _rrIntervals.value = rrValues
                }
            }
        }
    }

    private fun extractHeartRate(characteristic: BluetoothGattCharacteristic): Int {
        val format = if (characteristic.properties and 0x01 != 0) {
            BluetoothGattCharacteristic.FORMAT_UINT16
        } else {
            BluetoothGattCharacteristic.FORMAT_UINT8
        }
        return characteristic.getIntValue(format, 1) ?: 0
    }

    private fun extractRRIntervals(characteristic: BluetoothGattCharacteristic): List<Int> {
        val flags = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) ?: 0
        val hrFormat = flags and 0x01
        val energyExpendedStatus = (flags shr 3) and 0x01
        val rrStatus = (flags shr 4) and 0x01

        if (rrStatus == 0) return emptyList()

        var offset = if (hrFormat == 0) 2 else 3
        if (energyExpendedStatus == 1) offset += 2

        val rrIntervals = mutableListOf<Int>()
        val data = characteristic.value ?: return emptyList()
        
        while (offset + 1 < data.size) {
            val rr = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, offset) ?: break
            rrIntervals.add(rr)
            offset += 2
        }
        return rrIntervals
    }

    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _connectionState.value = "Bluetooth is disabled"
            return
        }
        
        if (isScanning) return

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = "BLE Scanner not available"
            return
        }

        _connectionState.value = "Scanning for HR monitors..."
        isScanning = true

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        if (isScanning) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = "Connecting to ${device.name ?: "Unknown"}..."
        // Use Handler to run connection on main thread to avoid some vendor-specific issues
        Handler(Looper.getMainLooper()).post {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        }
    }

    fun closeGatt() {
        bluetoothGatt?.let {
            it.close()
            bluetoothGatt = null
        }
    }

    fun disconnect() {
        stopScan()
        bluetoothGatt?.disconnect()
    }
}
