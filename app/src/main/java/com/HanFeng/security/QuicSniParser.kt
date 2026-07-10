package com.HanFeng.security

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

object QuicSniParser {

    data class QuicSniResult(
        val sniHost: String?,
        val alpnProtocols: List<String>,
        val echOffered: Boolean
    )

    private val initialSalt = byteArrayOf(
        0x38.toByte(), 0x76.toByte(), 0x2c.toByte(), 0xf7.toByte(),
        0xf5.toByte(), 0x59.toByte(), 0x34.toByte(), 0xb3.toByte(),
        0x4d.toByte(), 0x17.toByte(), 0x9a.toByte(), 0xe6.toByte(),
        0xa4.toByte(), 0xc8.toByte(), 0x0c.toByte(), 0xad.toByte(),
        0xcc.toByte(), 0xbb.toByte(), 0x7f.toByte(), 0x0a.toByte()
    )

    private const val MAX_KEY_CACHE_SIZE = 256
    private val keyCache = object : LinkedHashMap<String, QuicKeys>(MAX_KEY_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QuicKeys>): Boolean {
            return size > MAX_KEY_CACHE_SIZE
        }
    }
    private var keyCacheExpiresAt = 0L

    fun extractSniHost(quicPacket: ByteArray): String? {
        return extractQuicSni(quicPacket)?.sniHost
    }

    fun extractQuicSni(quicPacket: ByteArray): QuicSniResult? {
        if (quicPacket.size < 29) return null // minimum Initial packet size after header + AEAD

        var offset = 0

        // check QUIC Long Header: header_form=1, fixed_bit=1
        val firstByte = (quicPacket[offset].toInt() and 0xFF)
        if ((firstByte and 0x80) == 0) return null
        if ((firstByte and 0x40) == 0) return null

        // packet type from bits 4-5: 00 = Initial, 01 = 0-RTT, 10 = Handshake, 11 = Retry
        val packetType = (firstByte shr 4) and 0x03
        if (packetType != 0x00) return null // only handle Initial packets

        // packet number length from bits 0-1
        val pktNumLen = (firstByte and 0x03) + 1
        offset++

        // version (4 bytes)
        if (offset + 4 > quicPacket.size) return null
        val version = readInt(quicPacket, offset)
        if (version != QUIC_V1 && version != QUIC_V2 && version != QUIC_VERSION_NEGOTIATION) return null
        offset += 4

        // DCID length + DCID
        if (offset >= quicPacket.size) return null
        val dcidLen = (quicPacket[offset].toInt() and 0xFF)
        offset++
        if (offset + dcidLen > quicPacket.size) return null
        val dcid = quicPacket.copyOfRange(offset, offset + dcidLen)
        offset += dcidLen

        // SCID length + SCID
        if (offset >= quicPacket.size) return null
        val scidLen = (quicPacket[offset].toInt() and 0xFF)
        offset++
        if (offset + scidLen > quicPacket.size) return null
        offset += scidLen

        // token length (variable-length integer)
        val tokenLen = readVarInt(quicPacket, offset) ?: return null
        offset = tokenLen.second
        if (offset + tokenLen.first > quicPacket.size) return null
        offset += tokenLen.first.toInt()

        // payload length (variable-length integer)
        val payloadLenPair = readVarInt(quicPacket, offset) ?: return null
        offset = payloadLenPair.second
        val payloadLen = payloadLenPair.first.toInt()
        if (offset + pktNumLen + payloadLen > quicPacket.size) return null

        // packet number (pktNumLen bytes, but actually masked by header protection)
        val sampleOffset = offset + 4 // header protection sample starts at pktNum + 4
        if (sampleOffset > quicPacket.size) return null
        val protectedPayload = quicPacket.copyOfRange(offset, offset + pktNumLen + payloadLen)

        try {
            val keys = getCachedKeys(dcid)
            val decryptedPayload = decryptInitialPayload(quicPacket, offset, protectedPayload, keys)
            if (decryptedPayload == null) return null

            val cryptoData = extractCryptoFrameData(decryptedPayload) ?: return null
            val clientHello = TlsClientHelloParser.extractClientHelloInfo(cryptoData) ?: return null
            return QuicSniResult(
                sniHost = clientHello.sniHost,
                alpnProtocols = clientHello.offeredAlpnProtocols,
                echOffered = clientHello.encryptedClientHelloOffered
            )
        } catch (_: javax.crypto.AEADBadTagException) {
            return null
        } catch (_: javax.crypto.IllegalBlockSizeException) {
            return null
        } catch (_: IndexOutOfBoundsException) {
            return null
        } catch (_: Exception) {
            return null
        }
    }

    private const val QUIC_V1 = 0x00000001
    private const val QUIC_V2 = 0x6b3343cf.toInt()
    private const val QUIC_VERSION_NEGOTIATION = 0x00000000

    private data class QuicKeys(
        val key: ByteArray,
        val iv: ByteArray,
        val hp: ByteArray
    )

    private fun getCachedKeys(dcid: ByteArray): QuicKeys {
        val now = System.currentTimeMillis()
        val dcidKey = dcid.joinToString("") { "%02x".format(it) }
        synchronized(keyCache) {
            if (now - keyCacheExpiresAt > 300_000L) {
                keyCache.clear()
                keyCacheExpiresAt = now
            }
            keyCache[dcidKey]?.let { return it }
            val keys = deriveInitialKeys(dcid)
            keyCache[dcidKey] = keys
            return keys
        }
    }

    private fun deriveInitialKeys(dcid: ByteArray): QuicKeys {
        // HKDF-Extract with SHA-256
        val initialSecret = hkdfExtract(initialSalt, dcid)

        // HKDF-Expand-Label for client_initial_secret
        val clientInitialSecret = hkdfExpandLabel(initialSecret, "client in", ByteArray(0), 32)

        val key = hkdfExpandLabel(clientInitialSecret, "quic key", ByteArray(0), 16)
        val iv = hkdfExpandLabel(clientInitialSecret, "quic iv", ByteArray(0), 12)
        val hp = hkdfExpandLabel(clientInitialSecret, "quic hp", ByteArray(0), 16)

        return QuicKeys(key, iv, hp)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpandLabel(secret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
        // labeled_secret = "tls13 " + label
        val labeledInfo = buildHkdfLabel(label, context, length)
        return hkdfExpand(secret, labeledInfo, length)
    }

    private fun buildHkdfLabel(label: String, context: ByteArray, length: Int): ByteArray {
        val labelBytes = "tls13 $label".toByteArray(Charsets.US_ASCII)
        val result = ByteArray(2 + 1 + 2 + 1 + labelBytes.size + 1 + context.size)

        var offset = 0
        // length (2 bytes, big-endian)
        result[offset] = ((length shr 8) and 0xFF).toByte()
        result[offset + 1] = (length and 0xFF).toByte()
        offset += 2

        // label length (1 byte)
        result[offset] = labelBytes.size.toByte()
        offset += 1

        // label bytes
        System.arraycopy(labelBytes, 0, result, offset, labelBytes.size)
        offset += labelBytes.size

        // context length (1 byte)
        result[offset] = context.size.toByte()
        offset += 1

        // context bytes
        System.arraycopy(context, 0, result, offset, context.size)

        return result
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val hashLen = 32 // SHA-256

        val n = (length + hashLen - 1) / hashLen
        var t = ByteArray(0)
        val result = ByteArray(n * hashLen)

        for (i in 0 until n) {
            val input = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, input, 0, t.size)
            System.arraycopy(info, 0, input, t.size, info.size)
            input[input.size - 1] = (i + 1).toByte()
            t = mac.doFinal(input)
            System.arraycopy(t, 0, result, i * hashLen, t.size)
        }

        return result.copyOf(length)
    }

    private fun decryptInitialPayload(
        rawPacket: ByteArray,
        pktNumOffset: Int,
        protectedPayload: ByteArray,
        keys: QuicKeys
    ): ByteArray? {
        // Step 1: Remove header protection
        // Sample starts at pktNumOffset + 4
        val sampleOffset = pktNumOffset + 4
        if (sampleOffset >= rawPacket.size) return null
        val sampleLength = minOf(16, rawPacket.size - sampleOffset)
        val sample = rawPacket.copyOfRange(sampleOffset, sampleOffset + sampleLength)

        val mask = aesEcbEncrypt(keys.hp, sample)
        if (mask == null || mask.size < 5) return null

        // Unmask first byte (long header)
        val unmaskedByte0 = (rawPacket[0].toInt() and 0xFF xor (mask[0].toInt() and 0x0F)).toByte()
        val unmaskedPktNumLen = ((unmaskedByte0.toInt() and 0x03) + 1).coerceIn(1..4)

        // Unmask packet number
        val unmaskedPktNum = ByteArray(unmaskedPktNumLen)
        for (i in 0 until minOf(unmaskedPktNumLen, protectedPayload.size)) {
            unmaskedPktNum[i] = (protectedPayload[i].toInt() and 0xFF xor (mask[1 + i].toInt() and 0xFF)).toByte()
        }

        // Step 2: Decrypt payload
        // The payload to decrypt starts after the packet number
        val ciphertext = protectedPayload.copyOfRange(unmaskedPktNumLen, protectedPayload.size)
        if (ciphertext.size < 16) return null

        // Make IV by XORing packet number into the initial IV
        val fullIv = ByteArray(keys.iv.size)
        System.arraycopy(keys.iv, 0, fullIv, 0, keys.iv.size)
        for (i in 0 until minOf(unmaskedPktNum.size, fullIv.size)) {
            val ivIdx = fullIv.size - unmaskedPktNum.size + i
            if (ivIdx >= 0) {
                fullIv[ivIdx] = (fullIv[ivIdx].toInt() and 0xFF xor (unmaskedPktNum[i].toInt() and 0xFF)).toByte()
            }
        }

        // AES-GCM decrypt with associated data (the header up to payload start)
        val aad = rawPacket.copyOfRange(0, pktNumOffset + unmaskedPktNumLen)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, fullIv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), spec)
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            return null
        }
    }

    private fun aesEcbEncrypt(key: ByteArray, input: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val padded = if (input.size % 16 == 0) input else {
                val p = ByteArray((input.size / 16 + 1) * 16)
                System.arraycopy(input, 0, p, 0, input.size)
                p
            }
            cipher.doFinal(padded)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractCryptoFrameData(decryptedPayload: ByteArray): ByteArray? {
        var offset = 0
        val allCryptoData = mutableListOf<ByteArray>()

        while (offset < decryptedPayload.size) {
            val frameType = readVarInt(decryptedPayload, offset) ?: break
            offset = frameType.second
            val type = frameType.first

            if (type == 0x06L) {
                // CRYPTO frame: type(0x06) + offset(varint) + length(varint) + data
                val cryptoOffset = readVarInt(decryptedPayload, offset) ?: break
                offset = cryptoOffset.second
                val cryptoLength = readVarInt(decryptedPayload, offset) ?: break
                offset = cryptoLength.second
                val len = cryptoLength.first.toInt()
                if (offset + len > decryptedPayload.size) break
                val data = decryptedPayload.copyOfRange(offset, offset + len)
                allCryptoData.add(data)
                offset += len
            } else if (type == 0x00L) {
                // PADDING frame: skip padding bytes
                var padded = offset
                while (padded < decryptedPayload.size && (decryptedPayload[padded].toInt() and 0xFF) == 0x00) {
                    padded++
                }
                if (padded > offset) {
                    offset = padded
                    continue
                }
                break // no more frames
            } else if (type == 0x01L) {
                // PING frame: no payload
                continue
            } else {
                // Unknown frame: stop parsing to avoid infinite loop
                break
            }
        }

        if (allCryptoData.isEmpty()) return null

        // Combine all CRYPTO frame data
        var totalLen = 0
        for (data in allCryptoData) totalLen += data.size
        val combined = ByteArray(totalLen)
        var pos = 0
        for (data in allCryptoData) {
            System.arraycopy(data, 0, combined, pos, data.size)
            pos += data.size
        }
        return combined
    }

    private fun readVarInt(buffer: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset >= buffer.size) return null
        val first = (buffer[offset].toInt() and 0xFF)
        val len = when {
            (first shr 6) == 0 -> 1
            (first shr 6) == 1 -> 2
            (first shr 6) == 2 -> 4
            else -> 8
        }
        if (offset + len > buffer.size) return null
        var value = (first and 0x3F).toLong()
        for (i in 1 until len) {
            value = (value shl 8) or ((buffer[offset + i].toInt() and 0xFF).toLong())
        }
        return Pair(value, offset + len)
    }

    private fun readInt(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
    }
}
