package io.github.cosmickonnect.ckp

import android.net.Network
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Connection state
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    IDENTIFIED,
    PAIRING,
    ENCRYPTED
}

/**
 * Connection to a remote device using CKP
 */
open class CkpConnection(
    protected val deviceId: String,
    protected val address: String,
    protected val port: Int,
    protected val ourIdentity: Identity,
    protected val onMessage: suspend (CkpMessage) -> Unit,
    protected val onDisconnected: () -> Unit
) {
    protected val TAG = "CkpConnection"
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    protected var socket: Socket? = null
    protected var inputStream: InputStream? = null
    protected var outputStream: OutputStream? = null
    protected var readJob: Job? = null

    protected val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    protected val _peerIdentity = MutableStateFlow<Identity?>(null)
    val peerIdentity: StateFlow<Identity?> = _peerIdentity

    protected var sessionCrypto: SessionCrypto? = null
    protected var pairingKeyPair: CkpKeyPair? = null

    /**
     * Connect to the remote device
     */
    open suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = ConnectionState.CONNECTING
            Log.i(TAG, "Connecting to $address:$port")

            socket = Socket().apply {
                soTimeout = Protocol.CONNECTION_TIMEOUT_MS.toInt()
                connect(InetSocketAddress(address, port), Protocol.CONNECTION_TIMEOUT_MS.toInt())
            }

            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()

            _state.value = ConnectionState.CONNECTED

            // Send our identity
            val sessionNonce = CkpCrypto.generateSessionNonce()
            val identityMsg = ourIdentity.copy(sessionNonce = sessionNonce)
            sendMessage(identityMsg)

            // Read peer's identity
            val (peerMsg, _) = readMessage() ?: run {
                Log.e(TAG, "Failed to read peer identity")
                disconnect()
                return@withContext false
            }

            if (peerMsg is Identity) {
                _peerIdentity.value = peerMsg
                _state.value = ConnectionState.IDENTIFIED
                Log.i(TAG, "Connected to ${peerMsg.name} (${peerMsg.deviceId})")

                // Start reading messages
                startReading()
                return@withContext true
            } else {
                Log.e(TAG, "Expected Identity message, got ${peerMsg::class.simpleName}")
                disconnect()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            disconnect()
            return@withContext false
        }
    }

    /**
     * Disconnect from the remote device
     */
    fun disconnect() {
        try {
            readJob?.cancel()
            readJob = null

            // Send disconnect message if possible
            if (_state.value != ConnectionState.DISCONNECTED) {
                try {
                    val msg = Disconnect(reason = "user_request")
                    sendMessageBlocking(msg)
                } catch (e: Exception) {
                    // Ignore
                }
            }

            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect: ${e.message}")
        } finally {
            socket = null
            inputStream = null
            outputStream = null
            _state.value = ConnectionState.DISCONNECTED
            onDisconnected()
        }
    }

    /**
     * Send a message to the remote device
     */
    suspend fun sendMessage(message: CkpMessage): Boolean = withContext(Dispatchers.IO) {
        try {
            val flags = if (sessionCrypto != null) {
                MessageFlags(encrypted = true)
            } else {
                MessageFlags()
            }

            val data = message.encode(flags)
            Log.i(TAG, "sendMessage: ${message::class.simpleName}, ${data.size} bytes, outputStream=${outputStream != null}")
            if (outputStream == null) {
                Log.e(TAG, "sendMessage failed: outputStream is null")
                return@withContext false
            }
            outputStream?.write(data)
            outputStream?.flush()
            Log.i(TAG, "sendMessage: wrote ${data.size} bytes successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message: ${e.message}", e)
            false
        }
    }

    private fun sendMessageBlocking(message: CkpMessage) {
        val data = message.encode(MessageFlags())
        outputStream?.write(data)
        outputStream?.flush()
    }

    /**
     * Request pairing with this device
     */
    suspend fun requestPairing(): Boolean {
        pairingKeyPair = CkpCrypto.generateKeyPair()
        val request = PairRequest(
            deviceId = ourIdentity.deviceId,
            name = ourIdentity.name,
            publicKey = pairingKeyPair!!.publicKeyBytes()
        )
        _state.value = ConnectionState.PAIRING
        return sendMessage(request)
    }

    /**
     * Accept a pairing request
     * Returns Pair of (verificationCode, pairingKey)
     */
    suspend fun acceptPairing(peerPublicKey: ByteArray): Pair<String, ByteArray>? {
        pairingKeyPair = CkpCrypto.generateKeyPair()

        // Perform key exchange
        val sharedSecret = pairingKeyPair!!.keyExchange(peerPublicKey)
        val pairingKey = CkpCrypto.derivePairingKey(sharedSecret)
        val verificationCode = CkpCrypto.generateVerificationCode(sharedSecret)

        // Send response
        val response = PairResponse(
            accepted = true,
            publicKey = pairingKeyPair!!.publicKeyBytes()
        )
        sendMessage(response)

        return Pair(verificationCode, pairingKey)
    }

    /**
     * Reject a pairing request
     */
    suspend fun rejectPairing() {
        val response = PairResponse(
            accepted = false,
            reason = "user_rejected"
        )
        sendMessage(response)
        _state.value = ConnectionState.IDENTIFIED
    }

    /**
     * Complete pairing after user verification
     */
    fun completePairing(pairingKey: ByteArray) {
        // Set up session encryption
        val ourNonce = ourIdentity.sessionNonce ?: return
        val peerNonce = _peerIdentity.value?.sessionNonce ?: return

        sessionCrypto = SessionCrypto(pairingKey, ourNonce, peerNonce)
        _state.value = ConnectionState.ENCRYPTED
        Log.i(TAG, "Pairing completed - encrypted session established")
    }

    protected fun startReading() {
        readJob = scope.launch {
            while (isActive) {
                try {
                    val result = readMessage()
                    if (result == null) {
                        Log.w(TAG, "Connection closed by peer")
                        break
                    }

                    val (message, flags) = result
                    handleMessage(message, flags)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Read error: ${e.message}")
                    break
                }
            }
            disconnect()
        }
    }

    private suspend fun handleMessage(message: CkpMessage, flags: MessageFlags) {
        when (message) {
            is PairRequest -> {
                Log.i(TAG, "Pairing request from ${message.name}")
                // Notify the app to show pairing dialog
                onMessage(message)
            }
            is PairResponse -> {
                if (message.accepted && message.publicKey != null) {
                    // Complete key exchange
                    val sharedSecret = pairingKeyPair?.keyExchange(message.publicKey)
                    if (sharedSecret != null) {
                        val pairingKey = CkpCrypto.derivePairingKey(sharedSecret)
                        val code = CkpCrypto.generateVerificationCode(sharedSecret)
                        Log.i(TAG, "Pairing accepted - verification code: $code")
                        // The app should show this code and call completePairing after user confirmation
                        onMessage(message)
                    }
                } else {
                    Log.i(TAG, "Pairing rejected: ${message.reason}")
                    _state.value = ConnectionState.IDENTIFIED
                    onMessage(message)
                }
            }
            is Disconnect -> {
                Log.i(TAG, "Peer disconnected: ${message.reason}")
                disconnect()
            }
            else -> {
                // Forward all other messages to the handler
                onMessage(message)
            }
        }
    }

    protected suspend fun readMessage(): Pair<CkpMessage, MessageFlags>? = withContext(Dispatchers.IO) {
        try {
            val input = inputStream ?: return@withContext null

            // Read header (8 bytes)
            val header = ByteArray(8)
            var read = 0
            while (read < 8) {
                val n = input.read(header, read, 8 - read)
                if (n < 0) return@withContext null
                read += n
            }

            // Verify magic
            if (header[0] != Protocol.MAGIC[0] || header[1] != Protocol.MAGIC[1]) {
                Log.e(TAG, "Invalid magic bytes")
                return@withContext null
            }

            val flags = MessageFlags.fromByte(header[3])
            val length = ByteBuffer.wrap(header, 4, 4).int

            if (length > Protocol.MAX_MESSAGE_SIZE) {
                Log.e(TAG, "Message too large: $length bytes")
                return@withContext null
            }

            // Read payload
            val payload = ByteArray(length)
            read = 0
            while (read < length) {
                val n = input.read(payload, read, length - read)
                if (n < 0) return@withContext null
                read += n
            }

            // Construct full message for decoding
            val fullMessage = header + payload
            CkpMessage.decode(fullMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read message: ${e.message}")
            null
        }
    }
}

/**
 * Wrapper for an incoming CKP connection (already accepted socket)
 */
class CkpIncomingConnection(
    incomingSocket: java.net.Socket,
    ourIdentity: Identity,
    peerIdentity: Identity,
    onMessage: suspend (CkpMessage) -> Unit,
    onDisconnected: () -> Unit
) : CkpConnection(
    deviceId = peerIdentity.deviceId,
    address = incomingSocket.inetAddress.hostAddress ?: "",
    port = incomingSocket.port,
    ourIdentity = ourIdentity,
    onMessage = onMessage,
    onDisconnected = onDisconnected
) {
    init {
        // Set the socket and streams directly since we already have a connected socket
        socket = incomingSocket
        inputStream = incomingSocket.getInputStream()
        outputStream = incomingSocket.getOutputStream()

        // Set the peer identity directly since we already received it
        _peerIdentity.value = peerIdentity
        _state.value = ConnectionState.IDENTIFIED
    }

    // Override connect() to do nothing since we're already connected
    override suspend fun connect(): Boolean = true

    // Start reading messages (called by the connection manager after setup)
    fun beginReading() {
        startReading()
    }
}

/**
 * CKP connection using a pre-connected socket (for network-bound connections)
 */
class CkpNetworkConnection(
    socket: Socket,
    deviceId: String,
    ourIdentity: Identity,
    onMessage: suspend (CkpMessage) -> Unit,
    onDisconnected: () -> Unit
) : CkpConnection(
    deviceId = deviceId,
    address = socket.inetAddress?.hostAddress ?: "",
    port = socket.port,
    ourIdentity = ourIdentity,
    onMessage = onMessage,
    onDisconnected = onDisconnected
) {
    init {
        // Set the socket and streams directly since we already have a connected socket
        this.socket = socket
        inputStream = socket.getInputStream()
        outputStream = socket.getOutputStream()
        _state.value = ConnectionState.CONNECTED
    }

    // Override connect() to just do the handshake since socket is already connected
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Handshaking with pre-connected socket to $address:$port")

            // Send our identity
            val sessionNonce = CkpCrypto.generateSessionNonce()
            val identityMsg = ourIdentity.copy(sessionNonce = sessionNonce)
            sendMessage(identityMsg)

            // Read peer's identity
            val (peerMsg, _) = readMessage() ?: run {
                Log.e(TAG, "Failed to read peer identity")
                disconnect()
                return@withContext false
            }

            if (peerMsg is Identity) {
                _peerIdentity.value = peerMsg
                _state.value = ConnectionState.IDENTIFIED
                Log.i(TAG, "Connected to ${peerMsg.name} (${peerMsg.deviceId})")

                // Start reading messages
                startReading()
                return@withContext true
            } else {
                Log.e(TAG, "Expected Identity message, got ${peerMsg::class.simpleName}")
                disconnect()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed: ${e.message}")
            disconnect()
            return@withContext false
        }
    }
}

/**
 * Connection manager for CKP
 */
class CkpConnectionManager(
    private val ourIdentity: Identity,
    private val onEvent: suspend (CkpConnectionEvent) -> Unit
) {
    private val TAG = "CkpConnectionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connections = java.util.concurrent.ConcurrentHashMap<String, CkpConnection>()
    private var serverSocket: java.net.ServerSocket? = null
    private var listenerJob: Job? = null

    /**
     * Start listening for incoming connections
     */
    fun startListener(): Boolean {
        if (serverSocket != null) return true

        return try {
            serverSocket = java.net.ServerSocket(Protocol.TCP_PORT)
            Log.i(TAG, "Listening on port ${Protocol.TCP_PORT}")

            listenerJob = scope.launch {
                while (isActive) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Log.i(TAG, "Incoming connection from ${socket.inetAddress}")
                        handleIncomingConnection(socket)
                    } catch (e: java.net.SocketException) {
                        if (isActive) {
                            Log.w(TAG, "Server socket error: ${e.message}")
                        }
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listener: ${e.message}")
            false
        }
    }

    /**
     * Stop listening for connections
     */
    fun stopListener() {
        listenerJob?.cancel()
        listenerJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
    }

    private suspend fun handleIncomingConnection(socket: java.net.Socket) {
        // Create a connection handler for incoming connections
        scope.launch {
            try {
                val inputStream = socket.getInputStream()
                val outputStream = socket.getOutputStream()

                // Generate session nonce
                val sessionNonce = CkpCrypto.generateSessionNonce()
                val identityMsg = ourIdentity.copy(sessionNonce = sessionNonce)

                // Send our identity first
                val identityData = identityMsg.encode()
                outputStream.write(identityData)
                outputStream.flush()

                // Read peer's identity
                val header = ByteArray(8)
                var read = 0
                while (read < 8) {
                    val n = inputStream.read(header, read, 8 - read)
                    if (n < 0) {
                        socket.close()
                        return@launch
                    }
                    read += n
                }

                // Verify magic
                if (header[0] != Protocol.MAGIC[0] || header[1] != Protocol.MAGIC[1]) {
                    Log.e(TAG, "Invalid magic from incoming connection")
                    socket.close()
                    return@launch
                }

                val length = java.nio.ByteBuffer.wrap(header, 4, 4).int
                if (length > Protocol.MAX_MESSAGE_SIZE) {
                    Log.e(TAG, "Message too large from incoming connection")
                    socket.close()
                    return@launch
                }

                val payload = ByteArray(length)
                read = 0
                while (read < length) {
                    val n = inputStream.read(payload, read, length - read)
                    if (n < 0) {
                        socket.close()
                        return@launch
                    }
                    read += n
                }

                val fullMessage = header + payload
                val (message, _) = CkpMessage.decode(fullMessage)

                if (message is Identity) {
                    Log.i(TAG, "Incoming connection from ${message.name} (${message.deviceId})")

                    val deviceId = message.deviceId
                    // Create a CkpConnection wrapper for this incoming connection
                    val connection = CkpIncomingConnection(
                        incomingSocket = socket,
                        ourIdentity = ourIdentity,
                        peerIdentity = message,
                        onMessage = { msg -> handleMessage(deviceId, msg) },
                        onDisconnected = { handleDisconnected(deviceId) }
                    )

                    connections[deviceId] = connection
                    connection.beginReading()

                    onEvent(CkpConnectionEvent.Connected(deviceId, message.name))
                } else {
                    Log.e(TAG, "Expected Identity, got ${message::class.simpleName}")
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming connection: ${e.message}")
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Connect to a device
     */
    suspend fun connect(deviceId: String, address: String, port: Int): Boolean {
        if (connections.containsKey(deviceId)) {
            Log.d(TAG, "Already connected to $deviceId")
            return true
        }

        val connection = CkpConnection(
            deviceId = deviceId,
            address = address,
            port = port,
            ourIdentity = ourIdentity,
            onMessage = { msg -> handleMessage(deviceId, msg) },
            onDisconnected = { handleDisconnected(deviceId) }
        )

        val success = connection.connect()
        if (success) {
            connections[deviceId] = connection
            onEvent(CkpConnectionEvent.Connected(deviceId, connection.peerIdentity.value?.name ?: ""))
        }
        return success
    }

    /**
     * Connect to a device using a specific Network (for hotspot connections)
     */
    suspend fun connectWithNetwork(deviceId: String, address: String, port: Int, network: Network): Boolean {
        if (connections.containsKey(deviceId)) {
            Log.d(TAG, "Already connected to $deviceId")
            return true
        }

        // Create socket using the network's socket factory
        val socket = try {
            val factory = network.socketFactory
            val s = factory.createSocket()
            s.connect(InetSocketAddress(address, port), Protocol.CONNECTION_TIMEOUT_MS.toInt())
            s
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect via network: ${e.message}")
            return false
        }

        val connection = CkpNetworkConnection(
            socket = socket,
            deviceId = deviceId,
            ourIdentity = ourIdentity,
            onMessage = { msg -> handleMessage(deviceId, msg) },
            onDisconnected = { handleDisconnected(deviceId) }
        )

        val success = connection.connect()
        if (success) {
            connections[deviceId] = connection
            onEvent(CkpConnectionEvent.Connected(deviceId, connection.peerIdentity.value?.name ?: ""))
        }
        return success
    }

    /**
     * Disconnect from a device
     */
    fun disconnect(deviceId: String) {
        connections[deviceId]?.disconnect()
        connections.remove(deviceId)
    }

    /**
     * Send a message to a device
     */
    suspend fun sendMessage(deviceId: String, message: CkpMessage): Boolean {
        return connections[deviceId]?.sendMessage(message) ?: false
    }

    /**
     * Accept a pairing request from a device
     */
    fun acceptPairing(deviceId: String, peerPublicKey: ByteArray) {
        scope.launch {
            val connection = connections[deviceId]
            if (connection != null) {
                val result = connection.acceptPairing(peerPublicKey)
                if (result != null) {
                    val (code, pairingKey) = result
                    Log.i(TAG, "Pairing accepted for $deviceId, code: $code")
                    onEvent(CkpConnectionEvent.PairingAccepted(deviceId, pairingKey))
                }
            } else {
                Log.w(TAG, "Cannot accept pairing: no connection for $deviceId")
            }
        }
    }

    /**
     * Reject a pairing request from a device
     */
    fun rejectPairing(deviceId: String) {
        scope.launch {
            connections[deviceId]?.rejectPairing()
            onEvent(CkpConnectionEvent.PairingRejected(deviceId, "user_rejected"))
        }
    }

    /**
     * Get list of connected device IDs
     */
    fun getConnectedDevices(): List<String> = ArrayList(connections.keys)

    private suspend fun handleMessage(deviceId: String, message: CkpMessage) {
        when (message) {
            is Ping -> {
                onEvent(CkpConnectionEvent.PingReceived(deviceId, message.message))
                // Send pong
                sendMessage(deviceId, Pong())
            }
            is Clipboard -> {
                onEvent(CkpConnectionEvent.ClipboardReceived(deviceId, message.content))
            }
            is Notification -> {
                onEvent(CkpConnectionEvent.NotificationReceived(deviceId, message))
            }
            is FileOffer -> {
                onEvent(CkpConnectionEvent.FileOfferReceived(deviceId, message))
            }
            is FindDevice -> {
                onEvent(CkpConnectionEvent.FindDeviceReceived(deviceId))
            }
            is ShareUrl -> {
                onEvent(CkpConnectionEvent.UrlReceived(deviceId, message.url))
            }
            is ShareText -> {
                onEvent(CkpConnectionEvent.TextReceived(deviceId, message.text))
            }
            is PairRequest -> {
                onEvent(CkpConnectionEvent.PairingRequested(deviceId, message.name, message.publicKey))
            }
            is PairResponse -> {
                if (message.accepted) {
                    onEvent(CkpConnectionEvent.PairingAccepted(deviceId, message.publicKey ?: ByteArray(0)))
                } else {
                    onEvent(CkpConnectionEvent.PairingRejected(deviceId, message.reason ?: "unknown"))
                }
            }
            else -> {
                Log.d(TAG, "Unhandled message from $deviceId: ${message::class.simpleName}")
            }
        }
    }

    private fun handleDisconnected(deviceId: String) {
        connections.remove(deviceId)
        scope.launch {
            onEvent(CkpConnectionEvent.Disconnected(deviceId))
        }
    }
}

/**
 * Events from the connection manager
 */
sealed class CkpConnectionEvent {
    data class Connected(val deviceId: String, val deviceName: String) : CkpConnectionEvent()
    data class Disconnected(val deviceId: String) : CkpConnectionEvent()
    data class PingReceived(val deviceId: String, val message: String?) : CkpConnectionEvent()
    data class ClipboardReceived(val deviceId: String, val content: String) : CkpConnectionEvent()
    data class NotificationReceived(val deviceId: String, val notification: Notification) : CkpConnectionEvent()
    data class FileOfferReceived(val deviceId: String, val offer: FileOffer) : CkpConnectionEvent()
    data class FindDeviceReceived(val deviceId: String) : CkpConnectionEvent()
    data class UrlReceived(val deviceId: String, val url: String) : CkpConnectionEvent()
    data class TextReceived(val deviceId: String, val text: String) : CkpConnectionEvent()
    data class PairingRequested(val deviceId: String, val deviceName: String, val publicKey: ByteArray) : CkpConnectionEvent()
    data class PairingAccepted(val deviceId: String, val pairingKey: ByteArray) : CkpConnectionEvent()
    data class PairingRejected(val deviceId: String, val reason: String) : CkpConnectionEvent()
}
