package io.github.cosmickonnect.protocol

import android.content.Context
import android.util.Log
import io.github.cosmickonnect.protocol.tls.TlsHelper
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Handles TCP+TLS connection to a single device.
 */
class DeviceConnection(
    private val context: Context,
    private val deviceId: String,
    private val address: InetAddress,
    private val port: Int,
    private val onPacketReceived: (NetworkPacket) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var socket: Socket? = null
    private var sslSocket: SSLSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "DeviceConnection"
        private const val CONNECT_TIMEOUT_MS = 10000
    }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Attempting TCP connection to $address:$port")

            // Step 1: Plain TCP connection
            val socketAddress = java.net.InetSocketAddress(address, port)
            Log.i(TAG, "InetSocketAddress created: ${socketAddress.address}:${socketAddress.port}")
            socket = Socket()
            socket!!.connect(socketAddress, CONNECT_TIMEOUT_MS)
            Log.i(TAG, "TCP connected to $address:$port")

            // Step 2: Send identity packet (before TLS)
            val plainWriter = BufferedWriter(OutputStreamWriter(socket!!.getOutputStream()))
            val plainReader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

            val identity = DeviceIdentity.getIdentity(context)
            val identityPacket = identity.toIdentityPacket()
            plainWriter.write(identityPacket.toJson() + "\n")
            plainWriter.flush()

            // Step 3: Read remote identity
            val remoteIdentityJson = plainReader.readLine()
            if (remoteIdentityJson != null) {
                val remoteIdentity = NetworkPacket.fromJson(remoteIdentityJson)
                Log.d(TAG, "Received identity from: ${remoteIdentity.body}")
            }

            // Step 4: Upgrade to TLS
            val tlsHelper = TlsHelper(context)
            sslSocket = tlsHelper.upgradeToTls(socket!!, address.hostAddress ?: address.hostName)

            // Step 5: Set up buffered IO on TLS socket
            reader = BufferedReader(InputStreamReader(sslSocket!!.getInputStream()))
            writer = BufferedWriter(OutputStreamWriter(sslSocket!!.getOutputStream()))

            isConnected = true

            // Start reading loop
            scope.launch {
                readLoop()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            disconnect()
            false
        }
    }

    private suspend fun readLoop() {
        try {
            while (isConnected) {
                val line = withContext(Dispatchers.IO) {
                    reader?.readLine()
                }

                if (line == null) {
                    Log.d(TAG, "Connection closed by remote")
                    break
                }

                try {
                    val packet = NetworkPacket.fromJson(line)
                    onPacketReceived(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse packet: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (isConnected) {
                Log.e(TAG, "Read error: ${e.message}")
            }
        } finally {
            disconnect()
            onDisconnected()
        }
    }

    fun sendPacket(packet: NetworkPacket): Boolean {
        if (!isConnected) return false

        return try {
            synchronized(this) {
                writer?.write(packet.toJson() + "\n")
                writer?.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            false
        }
    }

    fun disconnect() {
        isConnected = false
        scope.cancel()

        try {
            reader?.close()
            writer?.close()
            sslSocket?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.d(TAG, "Error closing connection: ${e.message}")
        }

        reader = null
        writer = null
        sslSocket = null
        socket = null
    }
}
