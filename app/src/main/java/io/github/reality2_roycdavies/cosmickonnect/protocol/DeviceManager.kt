package io.github.reality2_roycdavies.cosmickonnect.protocol

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages discovered and connected devices.
 */
class DeviceManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connections = ConcurrentHashMap<String, DeviceConnection>()
    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    companion object {
        private const val TAG = "DeviceManager"
    }

    data class DiscoveredDevice(
        val id: String,
        val name: String,
        val type: String,
        val address: InetAddress,
        val port: Int,
        val lastSeen: Long = System.currentTimeMillis()
    )

    data class Device(
        val id: String,
        val name: String,
        val type: String,
        val paired: Boolean,
        val connected: Boolean
    )

    fun onDeviceDiscovered(
        deviceId: String,
        deviceName: String,
        deviceType: String,
        address: InetAddress,
        port: Int
    ) {
        Log.i(TAG, "onDeviceDiscovered: $deviceName at $address:$port")

        val device = DiscoveredDevice(
            id = deviceId,
            name = deviceName,
            type = deviceType,
            address = address,
            port = if (port > 0) port else NetworkPacket.DEFAULT_TCP_PORT  // Ensure valid port
        )
        discoveredDevices[deviceId] = device

        // If paired and not connected, connect automatically
        if (isPaired(deviceId) && !connections.containsKey(deviceId)) {
            scope.launch {
                connectToDevice(deviceId)
            }
        }

        updateDeviceList()
    }

    suspend fun connectToDevice(deviceId: String): Boolean {
        val discovered = discoveredDevices[deviceId] ?: return false

        if (connections.containsKey(deviceId)) {
            Log.d(TAG, "Already connected to $deviceId")
            return true
        }

        Log.i(TAG, "Connecting to ${discovered.name} at ${discovered.address}:${discovered.port}")

        try {
            val connection = DeviceConnection(
                context = context,
                deviceId = deviceId,
                address = discovered.address,
                port = discovered.port,
                onPacketReceived = { packet -> handlePacket(deviceId, packet) },
                onDisconnected = { onDeviceDisconnected(deviceId) }
            )

            if (connection.connect()) {
                connections[deviceId] = connection
                updateDeviceList()
                Log.i(TAG, "Connected to ${discovered.name}")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ${discovered.name}: ${e.message}")
        }

        return false
    }

    /**
     * Connect to a device with explicit address and port.
     * Used for BLE and Wi-Fi Direct discovered devices.
     */
    suspend fun connectToDevice(
        deviceId: String,
        deviceName: String,
        ipAddress: String,
        port: Int
    ): Boolean {
        // First register as discovered device
        try {
            val address = InetAddress.getByName(ipAddress)
            onDeviceDiscovered(deviceId, deviceName, "unknown", address, port)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid IP address: $ipAddress")
            return false
        }

        // Then connect
        return connectToDevice(deviceId)
    }

    fun disconnectDevice(deviceId: String) {
        connections[deviceId]?.disconnect()
        connections.remove(deviceId)
        updateDeviceList()
    }

    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        updateDeviceList()
    }

    fun sendPacket(deviceId: String, packet: NetworkPacket): Boolean {
        return connections[deviceId]?.sendPacket(packet) ?: false
    }

    fun isPaired(deviceId: String): Boolean {
        val prefs = context.getSharedPreferences("paired_devices", Context.MODE_PRIVATE)
        return prefs.contains(deviceId)
    }

    fun setPaired(deviceId: String, paired: Boolean) {
        val prefs = context.getSharedPreferences("paired_devices", Context.MODE_PRIVATE)
        if (paired) {
            prefs.edit().putBoolean(deviceId, true).apply()
        } else {
            prefs.edit().remove(deviceId).apply()
        }
        updateDeviceList()
    }

    /**
     * Request pairing with a device. This sends a pair request packet.
     */
    fun requestPairing(deviceId: String) {
        Log.i(TAG, "Requesting pairing with $deviceId")
        val body = kotlinx.serialization.json.buildJsonObject {
            put("pair", JsonPrimitive(true))
        }
        val packet = NetworkPacket(type = NetworkPacket.TYPE_PAIR, body = body)
        if (sendPacket(deviceId, packet)) {
            Log.i(TAG, "Pair request sent to $deviceId")
        } else {
            Log.e(TAG, "Failed to send pair request to $deviceId")
        }
    }

    /**
     * Request unpairing from a device.
     */
    fun requestUnpairing(deviceId: String) {
        Log.i(TAG, "Requesting unpairing from $deviceId")
        val body = kotlinx.serialization.json.buildJsonObject {
            put("pair", JsonPrimitive(false))
        }
        val packet = NetworkPacket(type = NetworkPacket.TYPE_PAIR, body = body)
        sendPacket(deviceId, packet)
        setPaired(deviceId, false)
    }

    private fun handlePacket(deviceId: String, packet: NetworkPacket) {
        Log.d(TAG, "Received packet from $deviceId: ${packet.type}")

        when (packet.type) {
            NetworkPacket.TYPE_PAIR -> handlePairPacket(deviceId, packet)
            NetworkPacket.TYPE_PING -> handlePingPacket(deviceId, packet)
            NetworkPacket.TYPE_FINDMYPHONE_REQUEST -> handleFindMyPhonePacket(deviceId)
            NetworkPacket.TYPE_CLIPBOARD -> handleClipboardPacket(deviceId, packet)
            NetworkPacket.TYPE_SHARE_REQUEST -> handleSharePacket(deviceId, packet)
            // Add more handlers as needed
        }
    }

    private fun handlePairPacket(deviceId: String, packet: NetworkPacket) {
        val pair = packet.body["pair"]?.jsonPrimitive?.booleanOrNull ?: false
        if (pair) {
            // Accept pairing request
            Log.i(TAG, "Pairing request from $deviceId")
            // TODO: Show pairing dialog to user
            // For now, auto-accept
            setPaired(deviceId, true)
            sendPairResponse(deviceId, true)
        } else {
            // Unpair request
            setPaired(deviceId, false)
        }
    }

    private fun sendPairResponse(deviceId: String, accept: Boolean) {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("pair", JsonPrimitive(accept))
        }
        val packet = NetworkPacket(type = NetworkPacket.TYPE_PAIR, body = body)
        sendPacket(deviceId, packet)
    }

    private fun handlePingPacket(deviceId: String, packet: NetworkPacket) {
        Log.i(TAG, "Ping received from $deviceId")
        // TODO: Show notification
    }

    private fun handleFindMyPhonePacket(deviceId: String) {
        Log.i(TAG, "Find my phone request from $deviceId")
        // TODO: Ring the phone
    }

    private fun handleClipboardPacket(deviceId: String, packet: NetworkPacket) {
        val content = packet.body["content"]?.jsonPrimitive?.contentOrNull
        if (content != null) {
            Log.i(TAG, "Clipboard received: ${content.substring(0, minOf(50, content.length))}...")
            // TODO: Set clipboard
        }
    }

    private fun handleSharePacket(deviceId: String, packet: NetworkPacket) {
        Log.i(TAG, "Share request from $deviceId")
        // TODO: Handle file/URL/text share
    }

    private fun onDeviceDisconnected(deviceId: String) {
        connections.remove(deviceId)
        updateDeviceList()
        Log.i(TAG, "Device disconnected: $deviceId")
    }

    private fun updateDeviceList() {
        val deviceList = discoveredDevices.values.map { discovered ->
            Device(
                id = discovered.id,
                name = discovered.name,
                type = discovered.type,
                paired = isPaired(discovered.id),
                connected = connections.containsKey(discovered.id)
            )
        }
        _devices.value = deviceList
    }
}
