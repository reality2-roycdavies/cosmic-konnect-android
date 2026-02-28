package io.github.reality2_roycdavies.cosmickonnect.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Discovered device information from Wi-Fi Direct.
 */
data class WifiDirectDevice(
    val deviceAddress: String,
    val deviceName: String,
    val status: Int,
    val isGroupOwner: Boolean = false
)

/**
 * Connection info when connected via Wi-Fi Direct.
 */
data class WifiDirectConnectionInfo(
    val groupOwnerAddress: InetAddress?,
    val isGroupOwner: Boolean,
    val groupFormed: Boolean
)

/**
 * Wi-Fi Direct Manager for Cosmic Konnect.
 *
 * Handles:
 * - Peer discovery
 * - Connection establishment
 * - Group formation
 * - Service discovery using TXT records
 */
@SuppressLint("MissingPermission")
class WifiDirectManager(
    private val context: Context,
    private val onDeviceDiscovered: (WifiDirectDevice) -> Unit,
    private val onConnectionEstablished: (WifiDirectConnectionInfo) -> Unit
) {
    private val TAG = "WifiDirectManager"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _devices = MutableStateFlow<List<WifiDirectDevice>>(emptyList())
    val devices: StateFlow<List<WifiDirectDevice>> = _devices

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private val _connectionInfo = MutableStateFlow<WifiDirectConnectionInfo?>(null)
    val connectionInfo: StateFlow<WifiDirectConnectionInfo?> = _connectionInfo

    // Service discovery constants
    companion object {
        const val SERVICE_TYPE = "_cosmickonnect._tcp"
        const val SERVICE_NAME = "CosmicKonnect"
    }

    /**
     * Initialize the Wi-Fi Direct manager.
     * @return true if initialization succeeded
     */
    fun initialize(): Boolean {
        try {
            wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (wifiP2pManager == null) {
                Log.e(TAG, "Wi-Fi Direct not supported on this device")
                return false
            }

            channel = wifiP2pManager?.initialize(context, context.mainLooper) {
                Log.w(TAG, "Wi-Fi Direct channel disconnected")
            }

            if (channel == null) {
                Log.e(TAG, "Failed to initialize Wi-Fi Direct channel")
                return false
            }

            registerReceiver()
            Log.i(TAG, "Wi-Fi Direct manager initialized")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Wi-Fi Direct: ${e.message}")
            return false
        }
    }

    private fun registerReceiver() {
        receiver = WifiDirectBroadcastReceiver()

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }
    }

    /**
     * Start discovering Wi-Fi Direct peers.
     */
    fun startDiscovery(): Boolean {
        try {
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct discovery started")
                    _isDiscovering.value = true
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Wi-Fi Direct discovery failed: ${reasonToString(reason)}")
                    _isDiscovering.value = false
                }
            })
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for discovery: ${e.message}")
            return false
        }
    }

    /**
     * Stop discovering peers.
     */
    fun stopDiscovery() {
        try {
            wifiP2pManager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct discovery stopped")
                    _isDiscovering.value = false
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Failed to stop discovery: ${reasonToString(reason)}")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
        }
    }

    /**
     * Connect to a Wi-Fi Direct peer.
     */
    fun connect(deviceAddress: String): Boolean {
        try {
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                this.wps.setup = WpsInfo.PBC
                // Prefer to be group client to let the other device be group owner
                // This makes it easier to establish TCP connections
                this.groupOwnerIntent = 0
            }

            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Connection initiated to $deviceAddress")
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Connection failed: ${reasonToString(reason)}")
                }
            })
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for connect: ${e.message}")
            return false
        }
    }

    /**
     * Disconnect from current Wi-Fi Direct group.
     */
    fun disconnect() {
        try {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Disconnected from Wi-Fi Direct group")
                    _connectionInfo.value = null
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Failed to disconnect: ${reasonToString(reason)}")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
        }
    }

    /**
     * Create a Wi-Fi Direct group (become group owner).
     */
    fun createGroup(): Boolean {
        try {
            wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct group created")
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to create group: ${reasonToString(reason)}")
                }
            })
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            return false
        }
    }

    /**
     * Register a local Cosmic Konnect service for discovery by other devices.
     */
    fun registerService(deviceId: String, deviceName: String, tcpPort: Int): Boolean {
        try {
            // Create a service info with our details
            val txtRecord = mapOf(
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "tcpPort" to tcpPort.toString(),
                "protocol" to "7"
            )

            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                SERVICE_NAME,
                SERVICE_TYPE,
                txtRecord
            )

            wifiP2pManager?.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Local service registered: $SERVICE_NAME")
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to register service: ${reasonToString(reason)}")
                }
            })
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            return false
        }
    }

    /**
     * Discover Cosmic Konnect services on nearby devices.
     */
    fun discoverServices(
        onServiceFound: (deviceAddress: String, deviceId: String, deviceName: String, tcpPort: Int) -> Unit
    ): Boolean {
        try {
            // Set up service discovery listener
            val txtListener = WifiP2pManager.DnsSdTxtRecordListener { fullDomain, record, device ->
                Log.d(TAG, "Found TXT record: $fullDomain from ${device.deviceAddress}")

                if (fullDomain.contains(SERVICE_TYPE)) {
                    val deviceId = record["deviceId"] ?: ""
                    val deviceName = record["deviceName"] ?: device.deviceName
                    val tcpPort = record["tcpPort"]?.toIntOrNull() ?: 1716

                    scope.launch {
                        onServiceFound(device.deviceAddress, deviceId, deviceName, tcpPort)
                    }
                }
            }

            val serviceListener = WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, device ->
                Log.d(TAG, "Found service: $instanceName ($registrationType) from ${device.deviceAddress}")
            }

            wifiP2pManager?.setDnsSdResponseListeners(channel, serviceListener, txtListener)

            // Add service discovery request
            val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
            wifiP2pManager?.addServiceRequest(channel, serviceRequest, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Service request added")
                    // Start service discovery
                    wifiP2pManager?.discoverServices(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.i(TAG, "Service discovery started")
                        }

                        override fun onFailure(reason: Int) {
                            Log.e(TAG, "Service discovery failed: ${reasonToString(reason)}")
                        }
                    })
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to add service request: ${reasonToString(reason)}")
                }
            })
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            return false
        }
    }

    /**
     * Request current connection info.
     */
    fun requestConnectionInfo() {
        try {
            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                if (info != null) {
                    val connectionInfo = WifiDirectConnectionInfo(
                        groupOwnerAddress = info.groupOwnerAddress,
                        isGroupOwner = info.isGroupOwner,
                        groupFormed = info.groupFormed
                    )
                    _connectionInfo.value = connectionInfo

                    if (info.groupFormed) {
                        Log.i(TAG, "Connected - Group owner: ${info.isGroupOwner}, Address: ${info.groupOwnerAddress}")
                        onConnectionEstablished(connectionInfo)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
        }
    }

    /**
     * Release resources.
     */
    fun release() {
        try {
            stopDiscovery()
            disconnect()
            receiver?.let { context.unregisterReceiver(it) }
            receiver = null
        } catch (e: Exception) {
            Log.w(TAG, "Error during release: ${e.message}")
        }
    }

    private fun reasonToString(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.ERROR -> "Internal error"
        WifiP2pManager.BUSY -> "Framework busy"
        else -> "Unknown error ($reason)"
    }

    private inner class WifiDirectBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _isEnabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.i(TAG, "Wi-Fi Direct state: ${if (_isEnabled.value) "enabled" else "disabled"}")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "Peers changed")
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        Log.i(TAG, "Wi-Fi Direct connected")
                        requestConnectionInfo()
                    } else {
                        Log.i(TAG, "Wi-Fi Direct disconnected")
                        _connectionInfo.value = null
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    Log.d(TAG, "This device: ${device?.deviceName}")
                }

                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                    _isDiscovering.value = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    Log.i(TAG, "Discovery state: ${if (_isDiscovering.value) "started" else "stopped"}")
                }
            }
        }

        private fun requestPeers() {
            try {
                wifiP2pManager?.requestPeers(channel) { peers ->
                    val deviceList = peers.deviceList.map { device ->
                        WifiDirectDevice(
                            deviceAddress = device.deviceAddress,
                            deviceName = device.deviceName,
                            status = device.status,
                            isGroupOwner = device.isGroupOwner
                        )
                    }

                    _devices.value = deviceList

                    deviceList.forEach { device ->
                        Log.d(TAG, "Peer: ${device.deviceName} (${device.deviceAddress})")
                        onDeviceDiscovered(device)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for requestPeers: ${e.message}")
            }
        }
    }
}
