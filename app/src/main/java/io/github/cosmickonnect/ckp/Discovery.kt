package io.github.cosmickonnect.ckp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Discovered device information
 */
data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val deviceType: DeviceType,
    val address: InetAddress,
    val tcpPort: Int,
    val capabilities: List<Capability>,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * UDP Discovery for CKP
 */
class CkpDiscovery(
    private val context: Context,
    private val ourIdentity: Identity,
    private val onDeviceDiscovered: (DiscoveredDevice) -> Unit
) {
    private val TAG = "CkpDiscovery"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var broadcastJob: Job? = null
    private var cleanupJob: Job? = null

    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val devices: StateFlow<Map<String, DiscoveredDevice>> = _devices

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    /**
     * Start discovery
     */
    fun start(): Boolean {
        if (_isRunning.value) return true

        try {
            socket = DatagramSocket(Protocol.UDP_DISCOVERY_PORT).apply {
                broadcast = true
                reuseAddress = true
            }

            _isRunning.value = true
            startReceiving()
            startBroadcasting()
            startCleanup()

            Log.i(TAG, "Discovery started on port ${Protocol.UDP_DISCOVERY_PORT}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery: ${e.message}")
            return false
        }
    }

    /**
     * Stop discovery
     */
    fun stop() {
        _isRunning.value = false
        receiveJob?.cancel()
        broadcastJob?.cancel()
        cleanupJob?.cancel()
        socket?.close()
        socket = null
        Log.i(TAG, "Discovery stopped")
    }

    /**
     * Send a broadcast now
     */
    fun broadcastNow() {
        scope.launch {
            sendBroadcast()
        }
    }

    private fun startReceiving() {
        receiveJob = scope.launch {
            val buffer = ByteArray(4096)

            while (isActive && _isRunning.value) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val data = buffer.copyOf(packet.length)
                    handlePacket(data, packet.address)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (_isRunning.value) {
                        Log.w(TAG, "Receive error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun startBroadcasting() {
        broadcastJob = scope.launch {
            // Initial broadcast
            sendBroadcast()

            // Periodic broadcasts
            while (isActive && _isRunning.value) {
                delay(Protocol.DISCOVERY_INTERVAL_MS)
                sendBroadcast()
            }
        }
    }

    private fun startCleanup() {
        cleanupJob = scope.launch {
            while (isActive && _isRunning.value) {
                delay(30_000) // Every 30 seconds

                val now = System.currentTimeMillis()
                val timeout = 60_000L // 60 seconds

                val stale = _devices.value.filter { (_, device) ->
                    now - device.lastSeen > timeout
                }

                if (stale.isNotEmpty()) {
                    val updated = _devices.value.toMutableMap()
                    stale.keys.forEach { id ->
                        updated.remove(id)
                        Log.i(TAG, "Device timeout: $id")
                    }
                    _devices.value = updated
                }
            }
        }
    }

    private suspend fun sendBroadcast() {
        try {
            val message = ourIdentity
            val data = message.encode()

            // Get broadcast addresses
            val broadcastAddresses = getBroadcastAddresses()

            for (addr in broadcastAddresses) {
                try {
                    val packet = DatagramPacket(
                        data,
                        data.size,
                        addr,
                        Protocol.UDP_DISCOVERY_PORT
                    )
                    socket?.send(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send to $addr: ${e.message}")
                }
            }

            Log.d(TAG, "Sent discovery broadcast to ${broadcastAddresses.size} addresses")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send broadcast: ${e.message}")
        }
    }

    private fun handlePacket(data: ByteArray, sourceAddress: InetAddress) {
        try {
            val (message, _) = CkpMessage.decode(data)

            if (message is Identity) {
                // Don't process our own broadcasts
                if (message.deviceId == ourIdentity.deviceId) return

                val device = DiscoveredDevice(
                    deviceId = message.deviceId,
                    name = message.name,
                    deviceType = message.deviceType,
                    address = sourceAddress,
                    tcpPort = message.tcpPort,
                    capabilities = message.capabilities
                )

                val isNew = !_devices.value.containsKey(device.deviceId)

                val updated = _devices.value.toMutableMap()
                updated[device.deviceId] = device
                _devices.value = updated

                if (isNew) {
                    Log.i(TAG, "Discovered: ${device.name} at ${device.address}:${device.tcpPort}")
                }

                onDeviceDiscovered(device)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse discovery packet: ${e.message}")
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()

        try {
            // Get WiFi broadcast address
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.let { info ->
                val ipAddress = info.ipAddress
                if (ipAddress != 0) {
                    // Calculate broadcast address (assuming /24 subnet)
                    val broadcast = (ipAddress and 0xFFFFFF) or (0xFF shl 24)
                    val bytes = byteArrayOf(
                        (broadcast and 0xFF).toByte(),
                        ((broadcast shr 8) and 0xFF).toByte(),
                        ((broadcast shr 16) and 0xFF).toByte(),
                        ((broadcast shr 24) and 0xFF).toByte()
                    )
                    addresses.add(InetAddress.getByAddress(bytes))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get WiFi broadcast: ${e.message}")
        }

        // Also try generic broadcast
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            // Ignore
        }

        // Get broadcast from network interfaces
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isUp && !iface.isLoopback) {
                    for (addr in iface.interfaceAddresses) {
                        addr.broadcast?.let { broadcast ->
                            if (!addresses.contains(broadcast)) {
                                addresses.add(broadcast)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get interface broadcasts: ${e.message}")
        }

        return addresses
    }
}
