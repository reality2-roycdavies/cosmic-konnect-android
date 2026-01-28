package io.github.cosmickonnect.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.cosmickonnect.protocol.NetworkPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovered device information from BLE.
 */
data class BleDiscoveredDevice(
    val bleAddress: String,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val ipAddresses: List<String>,
    val tcpPort: Int,
    val protocolVersion: Int,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * BLE Scanner for discovering Cosmic Konnect devices.
 *
 * Scans for devices advertising the Cosmic Konnect GATT service,
 * then connects to read their device information.
 */
class BleScanner(
    private val context: Context,
    private val onDeviceDiscovered: (BleDiscoveredDevice) -> Unit
) {
    private val TAG = "BleScanner"

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // Track discovered devices and their GATT connections
    private val discoveredDevices = ConcurrentHashMap<String, BleDiscoveredDevice>()
    private val pendingConnections = ConcurrentHashMap<String, BluetoothGatt>()

    private val _devices = MutableStateFlow<List<BleDiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<BleDiscoveredDevice>> = _devices

    /**
     * Initialize the BLE scanner.
     * @return true if initialization succeeded
     */
    fun initialize(): Boolean {
        try {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null) {
                Log.e(TAG, "Bluetooth not supported")
                return false
            }

            if (!bluetoothAdapter!!.isEnabled) {
                Log.e(TAG, "Bluetooth is not enabled")
                return false
            }

            scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BLE scanner not available")
                return false
            }

            Log.i(TAG, "BLE Scanner initialized")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BLE scanner: ${e.message}")
            return false
        }
    }

    /**
     * Start scanning for Cosmic Konnect devices.
     */
    fun startScanning(): Boolean {
        if (isScanning) {
            Log.w(TAG, "Already scanning")
            return true
        }

        try {
            // Filter for Cosmic Konnect service UUID
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true

            // Auto-stop after timeout
            handler.postDelayed({
                stopScanning()
            }, BleConstants.SCAN_TIMEOUT_MS)

            Log.i(TAG, "Started BLE scanning for Cosmic Konnect devices")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for scanning: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scanning: ${e.message}")
            return false
        }
    }

    /**
     * Stop scanning.
     */
    fun stopScanning() {
        if (!isScanning) return

        try {
            scanner?.stopScan(scanCallback)
            isScanning = false
            Log.i(TAG, "Stopped BLE scanning")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when stopping scan: ${e.message}")
        }
    }

    /**
     * Get list of discovered devices.
     */
    fun getDiscoveredDevices(): List<BleDiscoveredDevice> {
        return discoveredDevices.values.toList()
    }

    /**
     * Clean up resources.
     */
    fun release() {
        stopScanning()
        pendingConnections.values.forEach { gatt ->
            try {
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing GATT: ${e.message}")
            }
        }
        pendingConnections.clear()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val error = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error $errorCode"
            }
            Log.e(TAG, "BLE scan failed: $error")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val address = device.address

        // Skip if we're already connecting to this device
        if (pendingConnections.containsKey(address)) {
            return
        }

        // Skip if we already have full info for this device (seen recently)
        val existing = discoveredDevices[address]
        if (existing != null && System.currentTimeMillis() - existing.lastSeen < 30_000) {
            // Update RSSI
            discoveredDevices[address] = existing.copy(
                rssi = result.rssi,
                lastSeen = System.currentTimeMillis()
            )
            return
        }

        Log.i(TAG, "Found Cosmic Konnect device: $address (RSSI: ${result.rssi})")

        // Connect to read device information
        scope.launch {
            connectAndReadInfo(device, result.rssi)
        }
    }

    private fun connectAndReadInfo(device: BluetoothDevice, rssi: Int) {
        try {
            val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                private var deviceId: String? = null
                private var deviceName: String? = null
                private var deviceType: String? = null
                private var ipAddresses: String? = null
                private var tcpPort: String? = null
                private var protocolVersion: String? = null
                private var characteristicsToRead = mutableListOf<BluetoothGattCharacteristic>()

                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    try {
                        if (newState == BluetoothGatt.STATE_CONNECTED) {
                            Log.d(TAG, "Connected to ${device.address}, discovering services...")
                            gatt?.discoverServices()
                        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                            Log.d(TAG, "Disconnected from ${device.address}")
                            pendingConnections.remove(device.address)
                            gatt?.close()
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied: ${e.message}")
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Service discovery failed for ${device.address}")
                        disconnectAndCleanup(gatt)
                        return
                    }

                    try {
                        val service = gatt?.getService(BleConstants.SERVICE_UUID)
                        if (service == null) {
                            Log.w(TAG, "Cosmic Konnect service not found on ${device.address}")
                            disconnectAndCleanup(gatt)
                            return
                        }

                        // Queue characteristics to read
                        characteristicsToRead.clear()
                        service.getCharacteristic(BleConstants.CHAR_DEVICE_ID)?.let { characteristicsToRead.add(it) }
                        service.getCharacteristic(BleConstants.CHAR_DEVICE_NAME)?.let { characteristicsToRead.add(it) }
                        service.getCharacteristic(BleConstants.CHAR_DEVICE_TYPE)?.let { characteristicsToRead.add(it) }
                        service.getCharacteristic(BleConstants.CHAR_IP_ADDRESS)?.let { characteristicsToRead.add(it) }
                        service.getCharacteristic(BleConstants.CHAR_TCP_PORT)?.let { characteristicsToRead.add(it) }
                        service.getCharacteristic(BleConstants.CHAR_PROTOCOL_VERSION)?.let { characteristicsToRead.add(it) }

                        // Start reading
                        readNextCharacteristic(gatt)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied: ${e.message}")
                        disconnectAndCleanup(gatt)
                    }
                }

                private fun readNextCharacteristic(gatt: BluetoothGatt?) {
                    if (characteristicsToRead.isEmpty()) {
                        // All done, create device info
                        createDiscoveredDevice(gatt, rssi)
                        return
                    }

                    try {
                        val characteristic = characteristicsToRead.removeAt(0)
                        gatt?.readCharacteristic(characteristic)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied reading characteristic: ${e.message}")
                        disconnectAndCleanup(gatt)
                    }
                }

                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                        val value = characteristic.getStringValue(0) ?: ""

                        when (characteristic.uuid) {
                            BleConstants.CHAR_DEVICE_ID -> deviceId = value
                            BleConstants.CHAR_DEVICE_NAME -> deviceName = value
                            BleConstants.CHAR_DEVICE_TYPE -> deviceType = value
                            BleConstants.CHAR_IP_ADDRESS -> ipAddresses = value
                            BleConstants.CHAR_TCP_PORT -> tcpPort = value
                            BleConstants.CHAR_PROTOCOL_VERSION -> protocolVersion = value
                        }

                        Log.d(TAG, "Read ${characteristic.uuid}: $value")
                    }

                    // Read next characteristic
                    readNextCharacteristic(gatt)
                }

                private fun createDiscoveredDevice(gatt: BluetoothGatt?, rssi: Int) {
                    if (deviceId != null && deviceName != null) {
                        val bleDevice = BleDiscoveredDevice(
                            bleAddress = device.address,
                            deviceId = deviceId!!,
                            deviceName = deviceName!!,
                            deviceType = deviceType ?: "unknown",
                            ipAddresses = ipAddresses?.split(",")?.filter { it.isNotEmpty() } ?: emptyList(),
                            tcpPort = tcpPort?.toIntOrNull() ?: NetworkPacket.DEFAULT_TCP_PORT,
                            protocolVersion = protocolVersion?.toIntOrNull() ?: 7,
                            rssi = rssi
                        )

                        discoveredDevices[device.address] = bleDevice
                        updateDeviceList()

                        Log.i(TAG, "Discovered device via BLE: ${bleDevice.deviceName} (${bleDevice.deviceId})")
                        Log.i(TAG, "  IP addresses: ${bleDevice.ipAddresses}")

                        onDeviceDiscovered(bleDevice)
                    }

                    disconnectAndCleanup(gatt)
                }

                private fun disconnectAndCleanup(gatt: BluetoothGatt?) {
                    try {
                        gatt?.disconnect()
                        gatt?.close()
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Permission denied during cleanup: ${e.message}")
                    }
                    pendingConnections.remove(device.address)
                }
            }, BluetoothDevice.TRANSPORT_LE)

            if (gatt != null) {
                pendingConnections[device.address] = gatt
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied connecting to ${device.address}: ${e.message}")
        }
    }

    private fun updateDeviceList() {
        _devices.value = discoveredDevices.values.toList()
    }
}
