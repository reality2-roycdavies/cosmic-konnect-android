package io.github.reality2_roycdavies.cosmickonnect.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.reality2_roycdavies.cosmickonnect.ble.BleAdvertiser
import io.github.reality2_roycdavies.cosmickonnect.ble.BleDiscoveredDevice
import io.github.reality2_roycdavies.cosmickonnect.ble.BleScanner
import io.github.reality2_roycdavies.cosmickonnect.protocol.DeviceIdentity
import io.github.reality2_roycdavies.cosmickonnect.protocol.Discovery
import io.github.reality2_roycdavies.cosmickonnect.protocol.NetworkPacket
import io.github.reality2_roycdavies.cosmickonnect.wifidirect.WifiDirectConnectionInfo
import io.github.reality2_roycdavies.cosmickonnect.wifidirect.WifiDirectDevice
import io.github.reality2_roycdavies.cosmickonnect.wifidirect.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Discovery method that found the device.
 */
enum class DiscoveryMethod {
    UDP_BROADCAST,
    BLE,
    WIFI_DIRECT
}

/**
 * Unified discovered device information.
 */
data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val ipAddresses: List<String>,
    val tcpPort: Int,
    val discoveryMethod: DiscoveryMethod,
    val rssi: Int? = null,  // Signal strength (BLE only)
    val bleAddress: String? = null,  // BLE MAC address
    val wifiDirectAddress: String? = null,  // Wi-Fi Direct MAC address
    val lastSeen: Long = System.currentTimeMillis(),
    val hotspotSsid: String? = null,  // WiFi hotspot SSID for fallback connection
    val hotspotPassword: String? = null  // WiFi hotspot password
)

/**
 * Callback interface for discovery events.
 */
interface DiscoveryCallback {
    fun onDeviceDiscovered(device: DiscoveredDevice)
    fun onDeviceLost(deviceId: String)
    fun onConnectionAvailable(device: DiscoveredDevice, ipAddress: String, port: Int)
}

/**
 * Unified Discovery Manager for Cosmic Konnect.
 *
 * Coordinates multiple discovery mechanisms:
 * - UDP broadcast (traditional KDE Connect discovery)
 * - BLE GATT (discovers nearby devices via Bluetooth)
 * - Wi-Fi Direct (creates direct P2P connections)
 *
 * The manager tries all available methods and merges discovered devices
 * from different sources.
 */
class UnifiedDiscoveryManager(
    private val context: Context,
    private val callback: DiscoveryCallback,
    private val tcpPort: Int = NetworkPacket.DEFAULT_TCP_PORT
) {
    private val TAG = "UnifiedDiscovery"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Discovery components
    private var udpDiscovery: Discovery? = null
    private var bleAdvertiser: BleAdvertiser? = null
    private var bleScanner: BleScanner? = null
    private var wifiDirectManager: WifiDirectManager? = null

    // Merged device list
    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val devices: StateFlow<Map<String, DiscoveredDevice>> = _devices

    // Status flags
    private val _udpEnabled = MutableStateFlow(false)
    val udpEnabled: StateFlow<Boolean> = _udpEnabled

    private val _bleEnabled = MutableStateFlow(false)
    val bleEnabled: StateFlow<Boolean> = _bleEnabled

    private val _wifiDirectEnabled = MutableStateFlow(false)
    val wifiDirectEnabled: StateFlow<Boolean> = _wifiDirectEnabled

    /**
     * Initialize all discovery mechanisms.
     */
    fun initialize(): Boolean {
        Log.i(TAG, "Initializing unified discovery manager")

        var anySuccess = false

        // Initialize UDP discovery (always available)
        try {
            // UDP discovery is handled by the existing Discovery class
            // which is managed by the service
            anySuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize UDP discovery: ${e.message}")
        }

        // Initialize BLE if permissions available
        if (hasBlePermissions()) {
            try {
                initializeBle()
                anySuccess = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize BLE: ${e.message}")
            }
        } else {
            Log.w(TAG, "BLE permissions not granted")
        }

        // Initialize Wi-Fi Direct if permissions available
        if (hasWifiDirectPermissions()) {
            try {
                initializeWifiDirect()
                anySuccess = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Wi-Fi Direct: ${e.message}")
            }
        } else {
            Log.w(TAG, "Wi-Fi Direct permissions not granted")
        }

        return anySuccess
    }

    private fun initializeBle() {
        val identity = DeviceIdentity.getIdentity(context, tcpPort)

        // Create BLE advertiser with the configured TCP port
        bleAdvertiser = BleAdvertiser(context, tcpPort) { deviceId, deviceName, ipAddress ->
            Log.i(TAG, "BLE connection request from $deviceName")
            val device = DiscoveredDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = "unknown",
                ipAddresses = listOf(ipAddress),
                tcpPort = tcpPort,
                discoveryMethod = DiscoveryMethod.BLE
            )
            callback.onConnectionAvailable(device, ipAddress, tcpPort)
        }

        // Create BLE scanner
        bleScanner = BleScanner(context) { bleDevice ->
            handleBleDevice(bleDevice)
        }

        Log.i(TAG, "BLE components initialized")
    }

    private fun initializeWifiDirect() {
        wifiDirectManager = WifiDirectManager(
            context = context,
            onDeviceDiscovered = { device ->
                handleWifiDirectDevice(device)
            },
            onConnectionEstablished = { connectionInfo ->
                handleWifiDirectConnection(connectionInfo)
            }
        )

        Log.i(TAG, "Wi-Fi Direct manager initialized")
    }

    /**
     * Start all discovery mechanisms.
     */
    fun startDiscovery() {
        Log.i(TAG, "Starting unified discovery")

        // Start BLE
        if (hasBlePermissions() && bleAdvertiser != null && bleScanner != null) {
            scope.launch {
                try {
                    if (bleAdvertiser?.initialize() == true) {
                        bleAdvertiser?.startAdvertising()
                    }
                    if (bleScanner?.initialize() == true) {
                        bleScanner?.startScanning()
                    }
                    _bleEnabled.value = true
                    Log.i(TAG, "BLE discovery started")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start BLE: ${e.message}")
                }
            }
        }

        // Start Wi-Fi Direct
        if (hasWifiDirectPermissions() && wifiDirectManager != null) {
            scope.launch {
                try {
                    if (wifiDirectManager?.initialize() == true) {
                        val identity = DeviceIdentity.getIdentity(context)
                        wifiDirectManager?.registerService(identity.deviceId, identity.deviceName, NetworkPacket.DEFAULT_TCP_PORT)
                        wifiDirectManager?.startDiscovery()
                        wifiDirectManager?.discoverServices { deviceAddress, deviceId, deviceName, tcpPort ->
                            handleWifiDirectService(deviceAddress, deviceId, deviceName, tcpPort)
                        }
                        _wifiDirectEnabled.value = true
                        Log.i(TAG, "Wi-Fi Direct discovery started")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Wi-Fi Direct: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop all discovery mechanisms.
     */
    fun stopDiscovery() {
        Log.i(TAG, "Stopping unified discovery")

        // Stop BLE
        bleAdvertiser?.stopAdvertising()
        bleScanner?.stopScanning()
        _bleEnabled.value = false

        // Stop Wi-Fi Direct
        wifiDirectManager?.stopDiscovery()
        _wifiDirectEnabled.value = false
    }

    /**
     * Trigger a new scan on all mechanisms.
     */
    fun triggerScan() {
        Log.i(TAG, "Triggering discovery scan")

        // Trigger BLE scan
        if (_bleEnabled.value) {
            bleScanner?.startScanning()
        }

        // Trigger Wi-Fi Direct scan
        if (_wifiDirectEnabled.value) {
            wifiDirectManager?.startDiscovery()
        }
    }

    /**
     * Connect to a device using the best available method.
     */
    fun connectToDevice(deviceId: String): Boolean {
        val device = _devices.value[deviceId] ?: return false

        Log.i(TAG, "Connecting to ${device.deviceName} via ${device.discoveryMethod}")

        return when (device.discoveryMethod) {
            DiscoveryMethod.UDP_BROADCAST -> {
                // TCP connection will be handled by the caller
                device.ipAddresses.isNotEmpty()
            }
            DiscoveryMethod.BLE -> {
                // TCP connection using IP from BLE characteristics
                device.ipAddresses.isNotEmpty()
            }
            DiscoveryMethod.WIFI_DIRECT -> {
                // Need to establish Wi-Fi Direct connection first
                device.wifiDirectAddress?.let { addr ->
                    wifiDirectManager?.connect(addr)
                    true
                } ?: false
            }
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopDiscovery()
        bleScanner?.release()
        wifiDirectManager?.release()
    }

    private fun handleBleDevice(bleDevice: BleDiscoveredDevice) {
        Log.i(TAG, "BLE device discovered: ${bleDevice.deviceName}")

        val device = DiscoveredDevice(
            deviceId = bleDevice.deviceId,
            deviceName = bleDevice.deviceName,
            deviceType = bleDevice.deviceType,
            ipAddresses = bleDevice.ipAddresses,
            tcpPort = bleDevice.tcpPort,
            discoveryMethod = DiscoveryMethod.BLE,
            rssi = bleDevice.rssi,
            bleAddress = bleDevice.bleAddress,
            hotspotSsid = bleDevice.hotspotSsid,
            hotspotPassword = bleDevice.hotspotPassword
        )

        addOrUpdateDevice(device)

        // If hotspot credentials are available, try connecting to hotspot
        if (bleDevice.hotspotSsid != null && bleDevice.hotspotPassword != null) {
            Log.i(TAG, "Hotspot available: ${bleDevice.hotspotSsid} - attempting connection")
            connectToHotspot(bleDevice.hotspotSsid, bleDevice.hotspotPassword, device)
        } else if (bleDevice.ipAddresses.isNotEmpty()) {
            // If we have IP addresses, notify that connection is available
            callback.onConnectionAvailable(device, bleDevice.ipAddresses.first(), bleDevice.tcpPort)
        }
    }

    /**
     * Connect to a WiFi hotspot and then establish connection to the device.
     */
    private fun connectToHotspot(ssid: String, password: String, device: DiscoveredDevice) {
        scope.launch(Dispatchers.IO) {
            try {
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                if (wifiManager == null) {
                    Log.e(TAG, "WiFi manager not available")
                    return@launch
                }

                // Check if already connected to this hotspot
                val currentSsid = wifiManager.connectionInfo?.ssid?.trim('"')
                if (currentSsid == ssid) {
                    Log.i(TAG, "Already connected to hotspot $ssid")
                    // Get the gateway IP (hotspot host)
                    val dhcpInfo = wifiManager.dhcpInfo
                    if (dhcpInfo != null) {
                        val gatewayIp = intToIp(dhcpInfo.gateway)
                        Log.i(TAG, "Hotspot gateway: $gatewayIp")
                        callback.onConnectionAvailable(device, gatewayIp, device.tcpPort)
                    }
                    return@launch
                }

                // For Android 10+, use WifiNetworkSuggestion API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val suggestion = android.net.wifi.WifiNetworkSuggestion.Builder()
                        .setSsid(ssid)
                        .setWpa2Passphrase(password)
                        .build()

                    val suggestionsList = listOf(suggestion)
                    val status = wifiManager.addNetworkSuggestions(suggestionsList)

                    if (status == android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                        Log.i(TAG, "WiFi network suggestion added for $ssid")
                        // The system will auto-connect when appropriate
                        // For now, notify with the device's advertised IPs
                        if (device.ipAddresses.isNotEmpty()) {
                            callback.onConnectionAvailable(device, device.ipAddresses.first(), device.tcpPort)
                        }
                    } else {
                        Log.w(TAG, "Failed to add network suggestion: $status")
                    }
                } else {
                    // Legacy WiFi connection for older Android versions
                    @Suppress("DEPRECATION")
                    val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                        SSID = "\"$ssid\""
                        preSharedKey = "\"$password\""
                    }

                    @Suppress("DEPRECATION")
                    val netId = wifiManager.addNetwork(wifiConfig)
                    if (netId != -1) {
                        @Suppress("DEPRECATION")
                        wifiManager.enableNetwork(netId, true)
                        Log.i(TAG, "Connecting to hotspot $ssid")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to hotspot: ${e.message}")
            }
        }
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    private fun handleWifiDirectDevice(wifiDevice: WifiDirectDevice) {
        Log.i(TAG, "Wi-Fi Direct device discovered: ${wifiDevice.deviceName}")
        // Wi-Fi Direct devices don't have connection info until we connect
        // We use service discovery to identify Cosmic Konnect devices
    }

    private fun handleWifiDirectService(
        deviceAddress: String,
        deviceId: String,
        deviceName: String,
        tcpPort: Int
    ) {
        Log.i(TAG, "Wi-Fi Direct Cosmic Konnect service found: $deviceName")

        val device = DiscoveredDevice(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = "unknown",
            ipAddresses = emptyList(), // Will be available after P2P connection
            tcpPort = tcpPort,
            discoveryMethod = DiscoveryMethod.WIFI_DIRECT,
            wifiDirectAddress = deviceAddress
        )

        addOrUpdateDevice(device)
        callback.onDeviceDiscovered(device)
    }

    private fun handleWifiDirectConnection(connectionInfo: WifiDirectConnectionInfo) {
        Log.i(TAG, "Wi-Fi Direct connected, group owner: ${connectionInfo.isGroupOwner}")

        if (connectionInfo.groupFormed && connectionInfo.groupOwnerAddress != null) {
            val ip = connectionInfo.groupOwnerAddress.hostAddress ?: return

            // Find the device that triggered this connection
            val wifiDirectDevices = _devices.value.values.filter {
                it.discoveryMethod == DiscoveryMethod.WIFI_DIRECT
            }

            for (device in wifiDirectDevices) {
                // Update the device with the new IP
                val updatedDevice = device.copy(
                    ipAddresses = listOf(ip),
                    lastSeen = System.currentTimeMillis()
                )
                addOrUpdateDevice(updatedDevice)

                // Notify connection available
                if (!connectionInfo.isGroupOwner) {
                    callback.onConnectionAvailable(updatedDevice, ip, device.tcpPort)
                }
            }
        }
    }

    private fun addOrUpdateDevice(device: DiscoveredDevice) {
        val currentDevices = _devices.value.toMutableMap()
        val existing = currentDevices[device.deviceId]

        if (existing == null) {
            // New device
            currentDevices[device.deviceId] = device
            _devices.value = currentDevices
            callback.onDeviceDiscovered(device)
        } else {
            // Merge device info (prefer more recent, combine IPs)
            val mergedIps = (existing.ipAddresses + device.ipAddresses).distinct()
            val merged = device.copy(
                ipAddresses = mergedIps,
                bleAddress = device.bleAddress ?: existing.bleAddress,
                wifiDirectAddress = device.wifiDirectAddress ?: existing.wifiDirectAddress
            )
            currentDevices[device.deviceId] = merged
            _devices.value = currentDevices
        }
    }

    // Permission checks

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasWifiDirectPermissions(): Boolean {
        val hasLocation = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasNearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return hasLocation || hasNearbyWifi
    }
}
