package moe.shizuku.manager.adb

import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "AdbPairClient"

internal const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
internal const val kMaxPeerInfoSize = 8192
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2

internal const val kExportedKeyLabel = "adb-label\u0000"
internal const val kExportedKeySize = 64

internal const val kPairingPacketHeaderSize = 6

/**
 * 与 native adb_pairing.cpp 中 PairingContext_* JNI 函数对应的 Kotlin 类。
 *
 * native 端 JNI_OnLoad 用 FindClass 注册 native 方法,这里必须是 public class
 * (不能是 private 嵌套类),全限定名严格为 moe.shizuku.manager.adb.PairingContext。
 */
class PairingContext private constructor(private val nativePtr: Long) {

    val msg: ByteArray

    init {
        msg = nativeMsg(nativePtr)
    }

    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(`in`: ByteArray) = nativeEncrypt(nativePtr, `in`)

    fun decrypt(`in`: ByteArray) = nativeDecrypt(nativePtr, `in`)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean

    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {

        fun create(password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

internal class PeerInfo(val type: Byte, data: ByteArray) {

    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0.toByte()),
        ADB_DEVICE_GUID(0.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)
            put(data)
        }

        Log.d(TAG, "write PeerInfo ${toStringShort()}")
    }

    override fun toString(): String {
        return "PeerInfo(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "type=$type, data=${data.contentToString()}"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(kMaxPeerInfoSize - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

internal class PairingPacketHeader(val version: Byte, val type: Byte, val payload: Int) {

    enum class Type(val value: Byte) {
        SPAKE2_MSG(0.toByte()),
        PEER_INFO(1.toByte())
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)
            put(type)
            putInt(payload)
        }

        Log.d(TAG, "write PairingPacketHeader ${toStringShort()}")
    }

    override fun toString(): String {
        return "PairingPacketHeader(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
                Log.e(TAG, "PairingPacketHeader version mismatch (us=$kCurrentKeyHeaderVersion them=${version})")
                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Log.e(TAG, "Unknown PairingPacket type=${type}")
                return null
            }
            if (payload <= 0 || payload > kMaxPayloadSize) {
                Log.e(TAG, "header payload not within a safe payload size (size=${payload})")
                return null
            }

            val header = PairingPacketHeader(version, type, payload)
            Log.d(TAG, "read PairingPacketHeader ${header.toStringShort()}")
            return header
        }
    }
}
