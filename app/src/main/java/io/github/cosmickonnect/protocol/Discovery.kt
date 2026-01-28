package io.github.cosmickonnect.protocol

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * UDP discovery for KDE Connect protocol.
 * Listens on port 1716 for identity broadcasts and announces this device.
 */
class Discovery(
    private val context: Context,
    private val deviceManager: DeviceManager
) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val TAG = "Discovery"
        private const val PORT = 1716
        private const val BROADCAST_INTERVAL_MS = 5000L
    }

    fun start() {
        if (isRunning) {
            Log.w(TAG, "Discovery already running")
            return
        }
        isRunning = true
        Log.w(TAG, "Starting discovery service")

        // Acquire multicast lock to receive broadcast packets
        acquireMulticastLock()

        scope.launch {
            Log.w(TAG, "Starting listener coroutine")
            startListening()
        }

        scope.launch {
            Log.w(TAG, "Starting broadcast coroutine")
            startBroadcasting()
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        socket?.close()
        socket = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("CosmicKonnect")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()
            Log.w(TAG, "Multicast lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.w(TAG, "Multicast lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release multicast lock: ${e.message}")
        }
        multicastLock = null
    }

    private suspend fun startListening() {
        try {
            Log.w(TAG, "Binding to UDP port $PORT")
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PORT))
            }
            Log.w(TAG, "Successfully bound to port $PORT")

            val buffer = ByteArray(65536)

            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val data = String(packet.data, 0, packet.length)
                    Log.w(TAG, "Received UDP packet from ${packet.address}: ${data.substring(0, minOf(100, data.length))}")
                    handleDiscoveryPacket(data, packet.address)
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error receiving packet: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery listener: ${e.message}", e)
        }
    }

    private suspend fun startBroadcasting() {
        val identity = DeviceIdentity.getIdentity(context)
        val identityPacket = identity.toIdentityPacket()
        val message = identityPacket.toJson() + "\n"
        val data = message.toByteArray()
        Log.w(TAG, "Broadcasting identity: ${identity.deviceName} (${identity.deviceId})")

        while (isRunning) {
            try {
                // Broadcast to all network interfaces
                val broadcastAddresses = getBroadcastAddresses()
                Log.w(TAG, "Broadcasting to ${broadcastAddresses.size} addresses: $broadcastAddresses")
                for (address in broadcastAddresses) {
                    try {
                        val sendSocket = DatagramSocket()
                        sendSocket.broadcast = true
                        val packet = DatagramPacket(data, data.size, address, PORT)
                        sendSocket.send(packet)
                        sendSocket.close()
                        Log.d(TAG, "Broadcast sent to $address")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to broadcast to $address: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast error: ${e.message}", e)
            }

            delay(BROADCAST_INTERVAL_MS)
        }
    }

    private fun handleDiscoveryPacket(data: String, address: InetAddress) {
        try {
            val packet = NetworkPacket.fromJson(data.trim())
            if (packet.type == NetworkPacket.TYPE_IDENTITY) {
                val body = packet.body
                val deviceId = body["deviceId"]?.jsonPrimitive?.contentOrNull ?: return
                val deviceName = body["deviceName"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                val deviceType = body["deviceType"]?.jsonPrimitive?.contentOrNull ?: "desktop"

                // Parse tcpPort safely - it might be int or missing
                val tcpPort = try {
                    body["tcpPort"]?.jsonPrimitive?.int ?: 1716
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse tcpPort, using default: ${e.message}")
                    1716
                }

                // Ignore our own broadcasts
                val ourIdentity = DeviceIdentity.getIdentity(context)
                if (deviceId == ourIdentity.deviceId) {
                    Log.d(TAG, "Ignoring our own broadcast")
                    return
                }

                Log.w(TAG, "Discovered device: $deviceName ($deviceId) at $address:$tcpPort (port from packet)")

                deviceManager.onDeviceDiscovered(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    deviceType = deviceType,
                    address = address,
                    port = tcpPort
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse discovery packet: ${e.message}")
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()

        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                        interfaceAddress.broadcast?.let { broadcast ->
                            addresses.add(broadcast)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get broadcast addresses: ${e.message}")
        }

        // Fallback to common broadcast address
        if (addresses.isEmpty()) {
            try {
                addresses.add(InetAddress.getByName("255.255.255.255"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create fallback broadcast address")
            }
        }

        return addresses
    }
}
