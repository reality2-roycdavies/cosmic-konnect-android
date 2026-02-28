package io.github.reality2_roycdavies.cosmickonnect.ckp

import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Base interface for all CKP messages
 */
sealed interface CkpMessage {
    val type: Byte

    /**
     * Encode this message to bytes with header
     */
    fun encode(flags: MessageFlags = MessageFlags()): ByteArray {
        val payload = encodePayload()

        if (payload.size > Protocol.MAX_MESSAGE_SIZE) {
            throw IllegalStateException("Message too large: ${payload.size} bytes")
        }

        val buffer = ByteBuffer.allocate(8 + payload.size)
        buffer.put(Protocol.MAGIC)
        buffer.put(Protocol.VERSION)
        buffer.put(flags.toByte())
        buffer.putInt(payload.size)
        buffer.put(payload)

        return buffer.array()
    }

    /**
     * Encode the message payload using MessagePack
     */
    fun encodePayload(): ByteArray

    companion object {
        /**
         * Decode a message from bytes
         */
        fun decode(data: ByteArray): Pair<CkpMessage, MessageFlags> {
            if (data.size < 8) {
                throw IllegalArgumentException("Invalid message: too short")
            }

            val buffer = ByteBuffer.wrap(data)

            // Check magic
            val magic = ByteArray(2)
            buffer.get(magic)
            if (!magic.contentEquals(Protocol.MAGIC)) {
                throw IllegalArgumentException("Invalid magic bytes")
            }

            // Check version
            val version = buffer.get()
            if (version > Protocol.VERSION) {
                throw IllegalArgumentException("Unsupported protocol version: $version")
            }

            val flags = MessageFlags.fromByte(buffer.get())
            val length = buffer.int

            if (length > Protocol.MAX_MESSAGE_SIZE) {
                throw IllegalArgumentException("Message too large: $length bytes")
            }

            if (data.size < 8 + length) {
                throw IllegalArgumentException("Incomplete message")
            }

            val payload = ByteArray(length)
            buffer.get(payload)

            val message = decodePayload(payload)
            return message to flags
        }

        /**
         * Decode a message payload
         */
        private fun decodePayload(payload: ByteArray): CkpMessage {
            val unpacker = MessagePack.newDefaultUnpacker(payload)
            val mapSize = unpacker.unpackMapHeader()

            // First, find the type
            var type: Byte = 0
            val values = mutableMapOf<String, Any?>()

            repeat(mapSize) {
                val key = unpacker.unpackString()
                val value = unpackValue(unpacker)
                if (key == "type") {
                    type = (value as Number).toByte()
                } else {
                    values[key] = value
                }
            }

            return when (type) {
                MessageType.IDENTITY -> Identity.fromMap(values)
                MessageType.PAIR_REQUEST -> PairRequest.fromMap(values)
                MessageType.PAIR_RESPONSE -> PairResponse.fromMap(values)
                MessageType.PAIR_CONFIRM -> PairConfirm.fromMap(values)
                MessageType.PING -> Ping.fromMap(values)
                MessageType.PONG -> Pong()
                MessageType.CLIPBOARD -> Clipboard.fromMap(values)
                MessageType.NOTIFICATION -> Notification.fromMap(values)
                MessageType.NOTIFICATION_ACTION -> NotificationAction.fromMap(values)
                MessageType.FILE_OFFER -> FileOffer.fromMap(values)
                MessageType.FILE_ACCEPT -> FileAccept.fromMap(values)
                MessageType.FILE_REJECT -> FileReject.fromMap(values)
                MessageType.FILE_CHUNK -> FileChunk.fromMap(values)
                MessageType.FILE_COMPLETE -> FileComplete.fromMap(values)
                MessageType.FIND_DEVICE -> FindDevice()
                MessageType.SHARE_URL -> ShareUrl.fromMap(values)
                MessageType.SHARE_TEXT -> ShareText.fromMap(values)
                MessageType.MEDIA_CONTROL -> MediaControl.fromMap(values)
                MessageType.MEDIA_INFO -> MediaInfo.fromMap(values)
                MessageType.DISCONNECT -> Disconnect.fromMap(values)
                MessageType.ERROR -> ErrorMessage.fromMap(values)
                else -> throw IllegalArgumentException("Unknown message type: $type")
            }
        }

        private fun unpackValue(unpacker: MessageUnpacker): Any? {
            return when (unpacker.nextFormat.valueType) {
                org.msgpack.value.ValueType.NIL -> {
                    unpacker.unpackNil()
                    null
                }
                org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
                org.msgpack.value.ValueType.INTEGER -> unpacker.unpackLong()
                org.msgpack.value.ValueType.FLOAT -> unpacker.unpackDouble()
                org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
                org.msgpack.value.ValueType.BINARY -> {
                    val len = unpacker.unpackBinaryHeader()
                    val bytes = ByteArray(len)
                    unpacker.readPayload(bytes)
                    bytes
                }
                org.msgpack.value.ValueType.ARRAY -> {
                    val len = unpacker.unpackArrayHeader()
                    (0 until len).map { unpackValue(unpacker) }
                }
                org.msgpack.value.ValueType.MAP -> {
                    val len = unpacker.unpackMapHeader()
                    val map = mutableMapOf<String, Any?>()
                    repeat(len) {
                        val key = unpacker.unpackString()
                        map[key] = unpackValue(unpacker)
                    }
                    map
                }
                else -> null
            }
        }
    }
}

/**
 * Helper to create MessagePack payload
 */
fun packMessage(block: MessagePacker.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    val packer = MessagePack.newDefaultPacker(out)
    packer.block()
    packer.close()
    return out.toByteArray()
}

/**
 * Device identity message
 */
data class Identity(
    val deviceId: String,
    val name: String,
    val deviceType: DeviceType,
    val protocolVersion: Byte = Protocol.VERSION,
    val tcpPort: Int = Protocol.TCP_PORT,
    val capabilities: List<Capability> = defaultCapabilities,
    val sessionNonce: ByteArray? = null
) : CkpMessage {
    override val type: Byte = MessageType.IDENTITY

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(7 + if (sessionNonce != null) 1 else 0)
        packString("type"); packByte(type)
        packString("device_id"); packString(deviceId)
        packString("name"); packString(name)
        packString("device_type"); packString(deviceType.value)
        packString("protocol_version"); packByte(protocolVersion)
        packString("tcp_port"); packInt(tcpPort)
        packString("capabilities")
        packArrayHeader(capabilities.size)
        capabilities.forEach { packString(it.value) }
        if (sessionNonce != null) {
            packString("session_nonce")
            packBinaryHeader(sessionNonce.size)
            writePayload(sessionNonce)
        }
    }

    companion object {
        val defaultCapabilities = listOf(
            Capability.CLIPBOARD,
            Capability.FILES,
            Capability.NOTIFICATIONS,
            Capability.FIND_DEVICE,
            Capability.SHARE
        )

        fun fromMap(map: Map<String, Any?>): Identity {
            @Suppress("UNCHECKED_CAST")
            val capList = (map["capabilities"] as? List<Any>)?.mapNotNull {
                Capability.fromValue(it.toString())
            } ?: defaultCapabilities

            return Identity(
                deviceId = map["device_id"] as? String ?: "",
                name = map["name"] as? String ?: "",
                deviceType = DeviceType.fromValue(map["device_type"] as? String ?: "phone"),
                protocolVersion = (map["protocol_version"] as? Number)?.toByte() ?: Protocol.VERSION,
                tcpPort = (map["tcp_port"] as? Number)?.toInt() ?: Protocol.TCP_PORT,
                capabilities = capList,
                sessionNonce = map["session_nonce"] as? ByteArray
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return deviceId == other.deviceId && name == other.name
    }

    override fun hashCode(): Int = deviceId.hashCode()
}

/**
 * Pairing request
 */
data class PairRequest(
    val deviceId: String,
    val name: String,
    val publicKey: ByteArray
) : CkpMessage {
    override val type: Byte = MessageType.PAIR_REQUEST

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(4)
        packString("type"); packByte(type)
        packString("device_id"); packString(deviceId)
        packString("name"); packString(name)
        packString("public_key")
        packBinaryHeader(publicKey.size)
        writePayload(publicKey)
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): PairRequest {
            return PairRequest(
                deviceId = map["device_id"] as? String ?: "",
                name = map["name"] as? String ?: "",
                publicKey = map["public_key"] as? ByteArray ?: ByteArray(0)
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairRequest) return false
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int = deviceId.hashCode()
}

/**
 * Pairing response
 */
data class PairResponse(
    val accepted: Boolean,
    val publicKey: ByteArray? = null,
    val reason: String? = null
) : CkpMessage {
    override val type: Byte = MessageType.PAIR_RESPONSE

    override fun encodePayload(): ByteArray = packMessage {
        var count = 2
        if (publicKey != null) count++
        if (reason != null) count++

        packMapHeader(count)
        packString("type"); packByte(type)
        packString("accepted"); packBoolean(accepted)
        publicKey?.let {
            packString("public_key")
            packBinaryHeader(it.size)
            writePayload(it)
        }
        reason?.let {
            packString("reason"); packString(it)
        }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): PairResponse {
            return PairResponse(
                accepted = map["accepted"] as? Boolean ?: false,
                publicKey = map["public_key"] as? ByteArray,
                reason = map["reason"] as? String
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairResponse) return false
        return accepted == other.accepted
    }

    override fun hashCode(): Int = accepted.hashCode()
}

/**
 * Pairing confirmation
 */
data class PairConfirm(
    val proof: ByteArray
) : CkpMessage {
    override val type: Byte = MessageType.PAIR_CONFIRM

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(2)
        packString("type"); packByte(type)
        packString("proof")
        packBinaryHeader(proof.size)
        writePayload(proof)
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): PairConfirm {
            return PairConfirm(
                proof = map["proof"] as? ByteArray ?: ByteArray(0)
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairConfirm) return false
        return proof.contentEquals(other.proof)
    }

    override fun hashCode(): Int = proof.contentHashCode()
}

/**
 * Ping message
 */
data class Ping(
    val message: String? = null
) : CkpMessage {
    override val type: Byte = MessageType.PING

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(if (message != null) 2 else 1)
        packString("type"); packByte(type)
        message?.let {
            packString("message"); packString(it)
        }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): Ping {
            return Ping(message = map["message"] as? String)
        }
    }
}

/**
 * Pong response
 */
class Pong : CkpMessage {
    override val type: Byte = MessageType.PONG

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(1)
        packString("type"); packByte(type)
    }
}

/**
 * Clipboard content
 */
data class Clipboard(
    val content: String,
    val timestamp: Long
) : CkpMessage {
    override val type: Byte = MessageType.CLIPBOARD

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(3)
        packString("type"); packByte(type)
        packString("content"); packString(content)
        packString("timestamp"); packLong(timestamp)
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): Clipboard {
            return Clipboard(
                content = map["content"] as? String ?: "",
                timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0
            )
        }
    }
}

/**
 * Phone notification
 */
data class Notification(
    val id: String,
    val app: String,
    val title: String,
    val text: String,
    val icon: ByteArray? = null,
    val actions: List<String> = emptyList(),
    val timestamp: Long,
    val dismissable: Boolean = true,
    val silent: Boolean = false
) : CkpMessage {
    override val type: Byte = MessageType.NOTIFICATION

    override fun encodePayload(): ByteArray = packMessage {
        var count = 7
        if (icon != null) count++
        if (actions.isNotEmpty()) count++

        packMapHeader(count)
        packString("type"); packByte(type)
        packString("id"); packString(id)
        packString("app"); packString(app)
        packString("title"); packString(title)
        packString("text"); packString(text)
        packString("timestamp"); packLong(timestamp)
        packString("dismissable"); packBoolean(dismissable)
        packString("silent"); packBoolean(silent)
        icon?.let {
            packString("icon")
            packBinaryHeader(it.size)
            writePayload(it)
        }
        if (actions.isNotEmpty()) {
            packString("actions")
            packArrayHeader(actions.size)
            actions.forEach { packString(it) }
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): Notification {
            return Notification(
                id = map["id"] as? String ?: "",
                app = map["app"] as? String ?: "",
                title = map["title"] as? String ?: "",
                text = map["text"] as? String ?: "",
                icon = map["icon"] as? ByteArray,
                actions = (map["actions"] as? List<Any>)?.map { it.toString() } ?: emptyList(),
                timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0,
                dismissable = map["dismissable"] as? Boolean ?: true,
                silent = map["silent"] as? Boolean ?: false
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Notification) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Notification action
 */
data class NotificationAction(
    val id: String,
    val action: String,
    val replyText: String? = null
) : CkpMessage {
    override val type: Byte = MessageType.NOTIFICATION_ACTION

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(if (replyText != null) 4 else 3)
        packString("type"); packByte(type)
        packString("id"); packString(id)
        packString("action"); packString(action)
        replyText?.let {
            packString("reply_text"); packString(it)
        }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): NotificationAction {
            return NotificationAction(
                id = map["id"] as? String ?: "",
                action = map["action"] as? String ?: "",
                replyText = map["reply_text"] as? String
            )
        }
    }
}

/**
 * File transfer offer
 */
data class FileOffer(
    val transferId: String,
    val filename: String,
    val size: Long,
    val mimeType: String? = null,
    val checksum: String? = null
) : CkpMessage {
    override val type: Byte = MessageType.FILE_OFFER

    override fun encodePayload(): ByteArray = packMessage {
        var count = 4
        if (mimeType != null) count++
        if (checksum != null) count++

        packMapHeader(count)
        packString("type"); packByte(type)
        packString("transfer_id"); packString(transferId)
        packString("filename"); packString(filename)
        packString("size"); packLong(size)
        mimeType?.let { packString("mime_type"); packString(it) }
        checksum?.let { packString("checksum"); packString(it) }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): FileOffer {
            return FileOffer(
                transferId = map["transfer_id"] as? String ?: "",
                filename = map["filename"] as? String ?: "",
                size = (map["size"] as? Number)?.toLong() ?: 0,
                mimeType = map["mime_type"] as? String,
                checksum = map["checksum"] as? String
            )
        }
    }
}

/**
 * File accept
 */
data class FileAccept(
    val transferId: String,
    val chunkSize: Int? = null
) : CkpMessage {
    override val type: Byte = MessageType.FILE_ACCEPT

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(if (chunkSize != null) 3 else 2)
        packString("type"); packByte(type)
        packString("transfer_id"); packString(transferId)
        chunkSize?.let { packString("chunk_size"); packInt(it) }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): FileAccept {
            return FileAccept(
                transferId = map["transfer_id"] as? String ?: "",
                chunkSize = (map["chunk_size"] as? Number)?.toInt()
            )
        }
    }
}

/**
 * File reject
 */
data class FileReject(
    val transferId: String,
    val reason: String? = null
) : CkpMessage {
    override val type: Byte = MessageType.FILE_REJECT

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(if (reason != null) 3 else 2)
        packString("type"); packByte(type)
        packString("transfer_id"); packString(transferId)
        reason?.let { packString("reason"); packString(it) }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): FileReject {
            return FileReject(
                transferId = map["transfer_id"] as? String ?: "",
                reason = map["reason"] as? String
            )
        }
    }
}

/**
 * File chunk
 */
data class FileChunk(
    val transferId: String,
    val offset: Long,
    val data: ByteArray
) : CkpMessage {
    override val type: Byte = MessageType.FILE_CHUNK

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(4)
        packString("type"); packByte(type)
        packString("transfer_id"); packString(transferId)
        packString("offset"); packLong(offset)
        packString("data")
        packBinaryHeader(data.size)
        writePayload(data)
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): FileChunk {
            return FileChunk(
                transferId = map["transfer_id"] as? String ?: "",
                offset = (map["offset"] as? Number)?.toLong() ?: 0,
                data = map["data"] as? ByteArray ?: ByteArray(0)
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileChunk) return false
        return transferId == other.transferId && offset == other.offset
    }

    override fun hashCode(): Int = 31 * transferId.hashCode() + offset.hashCode()
}

/**
 * File complete
 */
data class FileComplete(
    val transferId: String,
    val success: Boolean,
    val checksum: String? = null
) : CkpMessage {
    override val type: Byte = MessageType.FILE_COMPLETE

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(if (checksum != null) 4 else 3)
        packString("type"); packByte(type)
        packString("transfer_id"); packString(transferId)
        packString("success"); packBoolean(success)
        checksum?.let { packString("checksum"); packString(it) }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): FileComplete {
            return FileComplete(
                transferId = map["transfer_id"] as? String ?: "",
                success = map["success"] as? Boolean ?: false,
                checksum = map["checksum"] as? String
            )
        }
    }
}

/**
 * Find device (ring phone)
 */
class FindDevice : CkpMessage {
    override val type: Byte = MessageType.FIND_DEVICE

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(1)
        packString("type"); packByte(type)
    }
}

/**
 * Share URL
 */
data class ShareUrl(
    val url: String
) : CkpMessage {
    override val type: Byte = MessageType.SHARE_URL

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(2)
        packString("type"); packByte(type)
        packString("url"); packString(url)
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): ShareUrl {
            return ShareUrl(url = map["url"] as? String ?: "")
        }
    }
}

/**
 * Share text
 */
data class ShareText(
    val text: String
) : CkpMessage {
    override val type: Byte = MessageType.SHARE_TEXT

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(2)
        packString("type"); packByte(type)
        packString("text"); packString(text)
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): ShareText {
            return ShareText(text = map["text"] as? String ?: "")
        }
    }
}

/**
 * Media control action
 */
enum class MediaAction(val value: String) {
    PLAY("play"),
    PAUSE("pause"),
    NEXT("next"),
    PREVIOUS("previous"),
    VOLUME("volume");

    companion object {
        fun fromValue(value: String): MediaAction? = entries.find { it.value == value }
    }
}

/**
 * Media control
 */
data class MediaControl(
    val action: MediaAction,
    val value: Int? = null
) : CkpMessage {
    override val type: Byte = MessageType.MEDIA_CONTROL

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(if (value != null) 3 else 2)
        packString("type"); packByte(type)
        packString("action"); packString(action.value)
        value?.let { packString("value"); packInt(it) }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): MediaControl {
            return MediaControl(
                action = MediaAction.fromValue(map["action"] as? String ?: "play") ?: MediaAction.PLAY,
                value = (map["value"] as? Number)?.toInt()
            )
        }
    }
}

/**
 * Media info
 */
data class MediaInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val duration: Long? = null,
    val position: Long? = null,
    val playing: Boolean = false,
    val artwork: ByteArray? = null
) : CkpMessage {
    override val type: Byte = MessageType.MEDIA_INFO

    override fun encodePayload(): ByteArray = packMessage {
        var count = 2 // type + playing
        if (title != null) count++
        if (artist != null) count++
        if (album != null) count++
        if (duration != null) count++
        if (position != null) count++
        if (artwork != null) count++

        packMapHeader(count)
        packString("type"); packByte(type)
        packString("playing"); packBoolean(playing)
        title?.let { packString("title"); packString(it) }
        artist?.let { packString("artist"); packString(it) }
        album?.let { packString("album"); packString(it) }
        duration?.let { packString("duration"); packLong(it) }
        position?.let { packString("position"); packLong(it) }
        artwork?.let {
            packString("artwork")
            packBinaryHeader(it.size)
            writePayload(it)
        }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): MediaInfo {
            return MediaInfo(
                title = map["title"] as? String,
                artist = map["artist"] as? String,
                album = map["album"] as? String,
                duration = (map["duration"] as? Number)?.toLong(),
                position = (map["position"] as? Number)?.toLong(),
                playing = map["playing"] as? Boolean ?: false,
                artwork = map["artwork"] as? ByteArray
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaInfo) return false
        return title == other.title && artist == other.artist
    }

    override fun hashCode(): Int = 31 * (title?.hashCode() ?: 0) + (artist?.hashCode() ?: 0)
}

/**
 * Disconnect message
 */
data class Disconnect(
    val reason: String? = null
) : CkpMessage {
    override val type: Byte = MessageType.DISCONNECT

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(if (reason != null) 2 else 1)
        packString("type"); packByte(type)
        reason?.let { packString("reason"); packString(it) }
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): Disconnect {
            return Disconnect(reason = map["reason"] as? String)
        }
    }
}

/**
 * Error message
 */
data class ErrorMessage(
    val code: Int,
    val message: String
) : CkpMessage {
    override val type: Byte = MessageType.ERROR

    override fun encodePayload(): ByteArray = packMessage {
        packMapHeader(3)
        packString("type"); packByte(type)
        packString("code"); packInt(code)
        packString("message"); packString(message)
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): ErrorMessage {
            return ErrorMessage(
                code = (map["code"] as? Number)?.toInt() ?: 1,
                message = map["message"] as? String ?: "Unknown error"
            )
        }

        // Error codes
        const val UNKNOWN_ERROR = 1
        const val PROTOCOL_ERROR = 2
        const val NOT_PAIRED = 3
        const val ENCRYPTION_ERROR = 4
        const val NOT_SUPPORTED = 5
        const val RATE_LIMITED = 6
    }
}
