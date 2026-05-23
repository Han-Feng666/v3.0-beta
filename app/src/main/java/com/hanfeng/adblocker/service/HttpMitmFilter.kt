package com.HanFeng.service

import com.HanFeng.data.RuleRepository
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

object HttpMitmFilter {
    private const val MAX_HTTP1_FILTER_BUFFER_BYTES = 512 * 1024
    private const val MAX_HTTP2_DATA_SAMPLE_BYTES = 8 * 1024
    private val requestMethods = listOf("GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ")
    private val compressibleEncodings = listOf("gzip", "br", "deflate", "zstd")
    private val responseAdKeywords = listOf(
        "adview", "adslot", "adunit", "advert", "banner", "splash", "reward", "preload", "promo", "promotion", "tracker", "tracking",
        "launch", "startup", "popup", "interstitial", "feedad", "open_screen", "openad"
    )
    private val htmlAdMarkers = listOf(
        "adsbygoogle",
        "google_ad",
        "ad-container",
        "ad-wrapper",
        "ad-banner",
        "adslot",
        "ad-unit",
        "adunit",
        "adservice",
        "splash-ad",
        "open-screen",
        "reward-video",
        "window.__slot__",
        "window.__ad__",
        "window.__ads__",
        "window.csj",
        "window.gdt",
        "window.pangle",
        "window.gromore",
        "window.topon",
        "window.tradplus",
        "window.applovin",
        "window.mintegral"
    )
    private val strongResponseAdKeywords = listOf(
        "advertisement",
        "adnxs",
        "admob",
        "adsdk",
        "adnetwork",
        "adservice",
        "ad_render",
        "adid",
        "adset",
        "materialid",
        "creativeid",
        "placementid",
        "slotid",
        "unitid",
        "impression",
        "clicktrack",
        "click_url",
        "show_url",
        "track_url",
        "win_notice",
        "deep_link",
        "download_url",
        "downloadurl",
        "landingpage",
        "landing_page",
        "landing_url",
        "landingurl",
        "open_screen",
        "openscreen",
        "interstitial",
        "reward_video",
        "rewardvideo",
        "fullscreen_video",
        "fullscreen",
        "native_express",
        "nativeexpress",
        "template_id",
        "templateid",
        "ecpm",
        "ecpm_level",
        "price_ratio",
        "adx",
        "rtb",
        "dsp",
        "ssp",
        "bidding",
        "playable",
        "playable_url",
        "playableurl",
        "endcard",
        "endcard_url",
        "endcardurl",
        "render_url",
        "renderurl",
        "material_url",
        "materialurl",
        "video_url",
        "videourl",
        "image_url",
        "imageurl",
        "callback_url",
        "callbackurl",
        "skip_time",
        "skiptime",
        "ad_info",
        "adinfo",
        "pangolin",
        "pangle",
        "gromore",
        "csj",
        "gdt",
        "sigmob",
        "mobvista",
        "mintegral",
        "applovin",
        "ironsource",
        "unityads",
        "vungle",
        "topon",
        "tradplus",
        "adscope",
        "kuaishouad",
        "ksad",
        "brand_banner",
        "feed_banner",
        "open_ad",
        "startup_ad"
    )
    private val suspiciousPathKeywords = listOf(
        "/ad", "/ads", "/advert", "/adview", "/adslot", "/adunit", "/adsdk", "/adservice", "/banner", "/splash", "/reward", "/promotion", "/promo", "/preload", "/material", "/creative", "/launch", "/startup", "/feedad", "/screenad", "/openad", "/popup", "/interstitial", "/floatad", "/bottomad"
    )
    private val suspiciousHeaderKeywords = listOf(
        "advert", "banner", "splash", "reward", "promo", "promotion", "track", "tracker", "interstitial", "popup", "openad"
    )
    private val suspiciousQueryKeywords = listOf(
        "ad", "ads", "adid", "adunit", "adslot", "placement", "promo", "promotion", "splash", "reward", "preload", "tracker", "creative", "material", "template", "ecpm", "playable", "endcard", "launch", "startup", "interstitial", "popup", "openad", "bottomad"
    )
    private val adTrackingHeaderFields = listOf(
        "click_url",
        "clickurl",
        "click_track_url",
        "clicktrackurl",
        "show_url",
        "showurl",
        "show_track_url",
        "track_url",
        "trackurl",
        "track_urls",
        "trackurls",
        "win_notice",
        "winnotice",
        "landing_page",
        "landingpage",
        "landing_url",
        "landingurl",
        "deep_link",
        "deeplink",
        "download_url",
        "downloadurl",
        "materialid",
        "material_id",
        "creativeid",
        "creative_id",
        "placementid",
        "placement_id",
        "slotid",
        "slot_id",
        "template_id",
        "templateid",
        "ecpm",
        "ecpm_level",
        "request_id",
        "ad_source",
        "adstyle",
        "ad_type",
        "interaction_type",
        "image_url",
        "video_url",
        "playable_url",
        "endcard_url",
        "render_url",
        "monitor_url",
        "monitor_urls",
        "expo_url",
        "expo_urls",
        "impression_url",
        "impression_urls",
        "callback_url",
        "skip_time",
        "ad_info",
        "ad_scene",
        "ad_position",
        "ad_location",
        "ad_switch",
        "reward_amount",
        "coin_reward",
        "chapter_reward"
    )
    private const val HTTP2_REQUEST_BLOCK_CANDIDATE_SCORE = 5
    private const val HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE = 4

    private val TRANSPARENT_1X1_GIF = byteArrayOf(
        0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(), 0x39.toByte(), 0x61.toByte(),
        0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x80.toByte(), 0x00.toByte(), 0x00.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x21.toByte(), 0xF9.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x2C.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x02.toByte(), 0x02.toByte(), 0x4C.toByte(), 0x01.toByte(), 0x00.toByte(),
        0x3B.toByte()
    )

    fun inspectRequest(session: TlsMitmSessionManager.TlsMitmSession, chunk: ByteArray): RequestInspection? {
        val text = decodeAscii(chunk) ?: return null
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return null
        if (requestMethods.none { text.startsWith(it) }) return null
        val lines = text.substring(0, headerEnd).split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines.first().split(' ')
        if (requestLine.size < 2) return null
        val hostHeader = lines.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.let(::normalizeAuthority)
            ?.ifBlank { null }
        return RequestInspection(
            method = requestLine[0],
            path = requestLine[1],
            host = hostHeader ?: session.host,
            httpVersion = requestLine.getOrNull(2) ?: "HTTP/1.1"
        )
    }

    fun rewriteRequestForMitm(
        session: TlsMitmSessionManager.TlsMitmSession,
        inspection: RequestInspection,
        chunk: ByteArray
    ): ByteArray {
        val text = decodeAscii(chunk) ?: return chunk
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return chunk
        if (requestMethods.none { text.startsWith(it) }) return chunk
        val directives = RuleRepository.getRequestRewriteDirectives(
            TlsMitmSessionManager.requireContext(),
            inspection.host,
            inspection.path,
            session.appName
        )
        val headerLines = text.substring(0, headerEnd).split("\r\n")
        if (headerLines.isEmpty()) return chunk
        var changed = false
        val rewrittenHeaders = headerLines.mapIndexedNotNull { index, line ->
            if (index == 0) return@mapIndexedNotNull line
            if (line.startsWith("Accept-Encoding:", ignoreCase = true)) {
                changed = true
                return@mapIndexedNotNull "Accept-Encoding: identity"
            }
            if (line.startsWith("TE:", ignoreCase = true) && compressibleEncodings.any { encoding -> line.contains(encoding, ignoreCase = true) }) {
                changed = true
                return@mapIndexedNotNull null
            }
            line
        }
        if (directives.cspValue != null) {
            changed = true
        }
        val requestLine = rewriteRequestLine(rewrittenHeaders.first(), directives.removeParams)
        if (requestLine != rewrittenHeaders.first()) changed = true
        if (!changed) return chunk
        val body = text.substring(headerEnd + 4)
        val finalHeaders = buildList {
            add(requestLine)
            addAll(rewrittenHeaders.drop(1))
            directives.cspValue?.let { add("X-HanFeng-CSP: $it") }
        }
        return (finalHeaders.joinToString("\r\n") + "\r\n\r\n" + body).toByteArray(StandardCharsets.ISO_8859_1)
    }

    fun shouldRewriteHttp1RequestHeaders(
        session: TlsMitmSessionManager.TlsMitmSession,
        inspection: RequestInspection
    ): Boolean {
        return shouldPreferDeepInspection(
            host = inspection.host,
            path = inspection.path,
            appName = session.appName
        )
    }

    fun shouldRewriteHttp2RequestHeaders(session: TlsMitmSessionManager.TlsMitmSession, inspection: Http2HeaderInspection?): Boolean {
        if (inspection?.requestLike != true) return false
        if (inspection.suspiciousScore > 0) return true
        return shouldPreferDeepInspection(
            host = inspection.authority,
            path = inspection.path,
            appName = session.appName,
            vendorHint = inspection.vendor
        )
    }

    fun rewriteHttp2RequestHeaders(headers: List<HpackDecoder.HeaderField>): Http2HeaderRewriteResult {
        if (headers.isEmpty()) return Http2HeaderRewriteResult(headers = headers, changed = false)
        var changed = false
        val rewritten = headers.mapNotNull { header ->
            val lowerName = header.name.lowercase()
            when {
                lowerName == "accept-encoding" && !header.value.equals("identity", ignoreCase = true) -> {
                    changed = true
                    HpackDecoder.HeaderField(header.name, "identity")
                }
                lowerName == "te" -> {
                    val normalized = header.value.lowercase()
                    if (normalized.contains("gzip") || normalized.contains("br") || normalized.contains("deflate") || normalized.contains("zstd")) {
                        changed = true
                        null
                    } else {
                        header
                    }
                }
                else -> header
            }
        }
        return Http2HeaderRewriteResult(headers = rewritten, changed = changed)
    }

    fun rewriteHttp2RequestHeaders(
        session: TlsMitmSessionManager.TlsMitmSession,
        inspection: Http2HeaderInspection,
        headers: List<HpackDecoder.HeaderField>
    ): Http2HeaderRewriteResult {
        val base = rewriteHttp2RequestHeaders(headers)
        val directives = RuleRepository.getRequestRewriteDirectives(
            TlsMitmSessionManager.requireContext(),
            inspection.authority,
            inspection.path.orEmpty(),
            session.appName
        )
        if (directives.removeParams.isEmpty() && directives.cspValue == null) return base
        var changed = base.changed
        val rewritten = base.headers.map { header ->
            if (header.name == ":path") {
                val updated = rewritePathOnly(header.value, directives.removeParams)
                if (updated != header.value) changed = true
                HpackDecoder.HeaderField(header.name, updated)
            } else {
                header
            }
        }.toMutableList()
        directives.cspValue?.let {
            rewritten += HpackDecoder.HeaderField("x-hanfeng-csp", it)
            changed = true
        }
        return Http2HeaderRewriteResult(rewritten, changed)
    }

    fun filterResponse(
        session: TlsMitmSessionManager.TlsMitmSession,
        chunk: ByteArray,
        requestInspection: RequestInspection?
    ): FilterResult {
        val text = decodeAscii(chunk) ?: return FilterResult.PassThrough(chunk, "binary-response")
        if (!text.startsWith("HTTP/1.")) return FilterResult.PassThrough(chunk, "non-http1-response")
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return FilterResult.PassThrough(chunk, "partial-response-headers")
        val headerText = text.substring(0, headerEnd)
        val headerLines = headerText.split("\r\n")
        val statusLine = headerLines.firstOrNull() ?: return FilterResult.PassThrough(chunk, "missing-status-line")
        val contentType = headerLines.firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val contentEncoding = headerLines.firstOrNull { it.startsWith("Content-Encoding:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val transferEncoding = headerLines.firstOrNull { it.startsWith("Transfer-Encoding:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val location = headerLines.firstOrNull { it.startsWith("Location:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val setCookie = headerLines.firstOrNull { it.startsWith("Set-Cookie:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val cosmeticSelectors = requestInspection?.let {
            RuleRepository.getCosmeticSelectors(TlsMitmSessionManager.requireContext(), it.host)
        }.orEmpty()
        val headerNeutralizeReason = inspectHttp1HeaderSignals(session, requestInspection, location, setCookie)
        if (headerNeutralizeReason != null) {
            val replacementBodyBytes = when {
                contentType.contains("application/json") -> "{}".toByteArray(StandardCharsets.UTF_8)
                contentType.contains("javascript") -> "".toByteArray(StandardCharsets.UTF_8)
                contentType.contains("text/html") -> "<html><body></body></html>".toByteArray(StandardCharsets.UTF_8)
                contentType.contains("image") -> TRANSPARENT_1X1_GIF
                else -> "".toByteArray(StandardCharsets.UTF_8)
            }
            val response = buildSyntheticResponse(statusLine, contentType, replacementBodyBytes)
            return FilterResult.Replaced(response, headerNeutralizeReason)
        }
        val bodyInspectionReason = shouldInspectHttp1ResponseBody(session, requestInspection, contentType)
        if (bodyInspectionReason == null) {
            return FilterResult.PassThrough(chunk, "response-body-skip:no-deep-inspection-target")
        }
        val bodyBytes = chunk.copyOfRange(headerEnd + 4, chunk.size)
        val decodedTransferBytes = if ("chunked" in transferEncoding) {
            decodeChunkedBody(bodyBytes) ?: return FilterResult.PassThrough(chunk, "invalid-chunked")
        } else {
            bodyBytes
        }
        val decodedBodyBytes = when {
            "br" in contentEncoding -> brotliBody(decodedTransferBytes) ?: return FilterResult.PassThrough(chunk, "brotli-decode-failed")
            "gzip" in contentEncoding -> gunzipBody(decodedTransferBytes) ?: return FilterResult.PassThrough(chunk, "gzip-decode-failed")
            "deflate" in contentEncoding -> inflateDeflateBody(decodedTransferBytes) ?: return FilterResult.PassThrough(chunk, "deflate-decode-failed")
            else -> decodedTransferBytes
        }
        val body = decodeAscii(decodedBodyBytes) ?: return FilterResult.PassThrough(chunk, "binary-response-body")
        val neutralizeReason = inspectHttp1BodySignals(session, requestInspection, contentType, body, cosmeticSelectors)
        if (neutralizeReason == null) {
            return FilterResult.PassThrough(chunk, "response-allowed")
        }
        val replacementBodyBytes = when {
            contentType.contains("application/json") -> "{}".toByteArray(StandardCharsets.UTF_8)
            contentType.contains("javascript") -> "".toByteArray(StandardCharsets.UTF_8)
            contentType.contains("text/html") -> buildCosmeticHtml(cosmeticSelectors).toByteArray(StandardCharsets.UTF_8)
            contentType.contains("image") -> TRANSPARENT_1X1_GIF
            else -> "".toByteArray(StandardCharsets.UTF_8)
        }
        val response = buildSyntheticResponse(statusLine, contentType, replacementBodyBytes)
        return FilterResult.Replaced(response, neutralizeReason, chunk.size)
    }

    fun maxHttp1FilterBufferBytes(): Int = MAX_HTTP1_FILTER_BUFFER_BYTES

    fun inspectBufferedHttp1Response(
        buffer: ByteArray,
        requestInspection: RequestInspection?
    ): BufferedHttp1Result {
        val text = decodeAscii(buffer) ?: return BufferedHttp1Result.Bypass("binary-response-buffer")
        if (!text.startsWith("HTTP/1.")) return BufferedHttp1Result.Bypass("non-http1-response")
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return BufferedHttp1Result.AwaitMore
        val headerBytes = headerEnd + 4
        val headerLines = text.substring(0, headerEnd).split("\r\n")
        val statusCode = headerLines.firstOrNull()
            ?.split(' ')
            ?.getOrNull(1)
            ?.toIntOrNull()
        val transferEncoding = headerLines.firstOrNull { it.startsWith("Transfer-Encoding:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: ""
        val contentLength = headerLines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.toLongOrNull()
        val bodyless = requestInspection?.method.equals("HEAD", ignoreCase = true) ||
            statusCode in 100..199 || statusCode == 204 || statusCode == 304
        if (bodyless) {
            val responseBytes = buffer.copyOfRange(0, headerBytes)
            val remainder = if (buffer.size > headerBytes) buffer.copyOfRange(headerBytes, buffer.size) else ByteArray(0)
            return BufferedHttp1Result.Ready(responseBytes, remainder)
        }
        if ("chunked" in transferEncoding) {
            val chunkedBodyBytes = detectCompleteChunkedBody(buffer, headerBytes) ?: return BufferedHttp1Result.AwaitMore
            val endIndex = headerBytes + chunkedBodyBytes
            val responseBytes = buffer.copyOfRange(0, endIndex)
            val remainder = if (buffer.size > endIndex) buffer.copyOfRange(endIndex, buffer.size) else ByteArray(0)
            return BufferedHttp1Result.Ready(responseBytes, remainder)
        }
        if (contentLength != null && contentLength >= 0L) {
            val totalLength = headerBytes + contentLength
            if (totalLength > Int.MAX_VALUE) return BufferedHttp1Result.Bypass("response-too-large")
            if (buffer.size < totalLength) return BufferedHttp1Result.AwaitMore
            val responseBytes = buffer.copyOfRange(0, totalLength.toInt())
            val remainder = if (buffer.size > totalLength) buffer.copyOfRange(totalLength.toInt(), buffer.size) else ByteArray(0)
            return BufferedHttp1Result.Ready(responseBytes, remainder)
        }
        return BufferedHttp1Result.AwaitMore
    }

    fun finalizeBufferedHttp1Response(buffer: ByteArray): BufferedHttp1Result {
        val text = decodeAscii(buffer) ?: return BufferedHttp1Result.Bypass("binary-response-buffer")
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd <= 0) return BufferedHttp1Result.Bypass("partial-response-headers")
        return BufferedHttp1Result.Ready(buffer, ByteArray(0))
    }

    fun inspectHttp2DataSample(
        session: TlsMitmSessionManager.TlsMitmSession,
        headerInspection: Http2HeaderInspection?,
        currentSample: ByteArray,
        incomingFragment: ByteArray
    ): Http2DataInspection? {
        if (incomingFragment.isEmpty()) return null
        if (headerInspection?.responseLike != true) return null
        val combinedSample = appendSample(currentSample, incomingFragment, MAX_HTTP2_DATA_SAMPLE_BYTES)
        val contentType = headerInspection.contentType?.lowercase().orEmpty()
        val targetedContentType = contentType.contains("json") ||
            contentType.contains("javascript") ||
            contentType.contains("html") ||
            contentType.contains("text")
        if (contentType.isNotBlank() && !targetedContentType) {
            return null
        }
        val decoded = decodeAscii(combinedSample) ?: return null
        val lowerBody = decoded.lowercase()
        val context = TlsMitmSessionManager.requireContext()
        val vendor = headerInspection.vendor.ifBlank {
            RuleRepository.classifyVendorFromHints(context, headerInspection.authority, session.appName)
        }
        val aggressiveNovelTarget = RuleRepository.shouldAggressivelyBlockForNovelApp(
            context,
            headerInspection.authority,
            session.appName,
            vendor
        )
        val bodySignals = inspectAdBodySignals(lowerBody)
        if (bodySignals.reasons.isEmpty()) return null
        var suspiciousScore = bodySignals.score + if (targetedContentType) 1 else 0
        if (isKnownAdVendor(vendor)) suspiciousScore += 1
        if (aggressiveNovelTarget) suspiciousScore += 2
        if (suspiciousScore < 2) return null
        val preview = decoded.replace('\r', ' ').replace('\n', ' ').take(160)
        val reasons = bodySignals.reasons.toMutableList()
        if (isKnownAdVendor(vendor)) reasons += "vendor:$vendor"
        if (aggressiveNovelTarget) reasons += "novel-app-aggressive"
        return Http2DataInspection(
            suspiciousScore = suspiciousScore,
            suspiciousReasons = reasons.distinct(),
            confidence = if (suspiciousScore >= 4) "high" else "medium",
            samplePreview = preview,
            vendor = vendor,
            combinedSample = combinedSample
        )
    }

    private fun decodeChunkedBody(body: ByteArray): ByteArray? {
        var offset = 0
        val output = ByteArrayOutputStream(body.size)
        while (offset < body.size) {
            val sizeLineEnd = indexOfCrlf(body, offset)
            if (sizeLineEnd < 0) return null
            val sizeLine = runCatching {
                String(body, offset, sizeLineEnd - offset, StandardCharsets.ISO_8859_1)
            }.getOrNull() ?: return null
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            offset = sizeLineEnd + 2
            if (chunkSize == 0) {
                return output.toByteArray()
            }
            if (offset + chunkSize > body.size) return null
            output.write(body, offset, chunkSize)
            offset += chunkSize
            if (offset + 2 > body.size || body[offset] != '\r'.code.toByte() || body[offset + 1] != '\n'.code.toByte()) {
                return null
            }
            offset += 2
        }
        return null
    }

    private fun gunzipBody(body: ByteArray): ByteArray? {
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(body)).use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    private fun brotliBody(body: ByteArray): ByteArray? {
        return runCatching {
            BrotliInputStream(ByteArrayInputStream(body)).use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    private fun inflateDeflateBody(body: ByteArray): ByteArray? {
        return runCatching {
            InflaterInputStream(ByteArrayInputStream(body)).use { input ->
                input.readBytes()
            }
        }.getOrNull()
    }

    private fun indexOfCrlf(data: ByteArray, start: Int): Int {
        var index = start
        while (index + 1 < data.size) {
            if (data[index] == '\r'.code.toByte() && data[index + 1] == '\n'.code.toByte()) {
                return index
            }
            index += 1
        }
        return -1
    }

    private fun detectCompleteChunkedBody(buffer: ByteArray, bodyStart: Int): Int? {
        var offset = bodyStart
        while (offset < buffer.size) {
            val sizeLineEnd = indexOfCrlf(buffer, offset)
            if (sizeLineEnd < 0) return null
            val sizeLine = runCatching {
                String(buffer, offset, sizeLineEnd - offset, StandardCharsets.ISO_8859_1)
            }.getOrNull() ?: return null
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            offset = sizeLineEnd + 2
            if (chunkSize == 0) {
                val trailerEnd = findChunkedTrailerEnd(buffer, offset)
                return trailerEnd?.minus(bodyStart)
            }
            if (offset + chunkSize + 2 > buffer.size) return null
            offset += chunkSize
            if (buffer[offset] != '\r'.code.toByte() || buffer[offset + 1] != '\n'.code.toByte()) return null
            offset += 2
        }
        return null
    }

    private fun findChunkedTrailerEnd(buffer: ByteArray, trailerStart: Int): Int? {
        if (trailerStart + 1 >= buffer.size) return null
        if (buffer[trailerStart] == '\r'.code.toByte() && buffer[trailerStart + 1] == '\n'.code.toByte()) {
            return trailerStart + 2
        }
        var offset = trailerStart
        while (offset + 3 < buffer.size) {
            if (buffer[offset] == '\r'.code.toByte() &&
                buffer[offset + 1] == '\n'.code.toByte() &&
                buffer[offset + 2] == '\r'.code.toByte() &&
                buffer[offset + 3] == '\n'.code.toByte()
            ) {
                return offset + 4
            }
            offset += 1
        }
        return null
    }

    private fun appendSample(existing: ByteArray, incoming: ByteArray, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) return ByteArray(0)
        if (existing.size >= maxBytes) return existing.copyOf(maxBytes)
        val remaining = maxBytes - existing.size
        val addition = if (incoming.size <= remaining) incoming else incoming.copyOf(remaining)
        return existing + addition
    }

    private fun inspectHttp1HeaderSignals(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        location: String,
        setCookie: String
    ): String? {
        val context = TlsMitmSessionManager.requireContext()
        val host = normalizeAuthority(requestInspection?.host ?: session.host)
        if (host.isBlank()) return null
        val lowerPath = requestInspection?.path?.lowercase().orEmpty()
        if (RuleRepository.isBlocked(context, host, appName = session.appName)) return "neutralized-blocked-host"
        if (RuleRepository.isUrlBlocked(context, host, lowerPath, session.appName)) return "neutralized-blocked-url"
        if (RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, host, lowerPath, session.appName)) {
            return "neutralized-novel-protected-path"
        }
        val vendor = RuleRepository.classifyVendorFromHints(context, host, session.appName)
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(context, host, session.appName, vendor)) {
            return "neutralized-novel-app-aggressive"
        }
        if (looksLikeSuspiciousHttpPath(lowerPath)) {
            return "neutralized-suspicious-path"
        }
        val headerTrackingHits = adTrackingHeaderFields.count { field ->
            location.contains(field) || setCookie.contains(field)
        }
        if (headerTrackingHits >= 2) {
            return "neutralized-header-tracking"
        }
        if (isKnownAdVendor(vendor) && (strongResponseAdKeywords.any { location.contains(it) } || strongResponseAdKeywords.any { setCookie.contains(it) })) {
            return "neutralized-header-vendor-signal"
        }
        return null
    }

    private fun shouldInspectHttp1ResponseBody(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        contentType: String
    ): String? {
        if (contentType.isBlank()) return null
        val targetedContentType = contentType.contains("text/html") ||
            contentType.contains("json") ||
            contentType.contains("javascript")
        if (!targetedContentType) return null
        val host = normalizeAuthority(requestInspection?.host ?: session.host)
        // 跳过白名单域名
        if (RuleRepository.isWhitelistedDomain(host)) return null
        val shouldInspect = shouldPreferDeepInspection(
            host = host,
            path = requestInspection?.path,
            appName = session.appName
        )
        return if (shouldInspect) "deep-inspection-target" else null
    }

    private fun inspectHttp1BodySignals(
        session: TlsMitmSessionManager.TlsMitmSession,
        requestInspection: RequestInspection?,
        contentType: String,
        body: String,
        cosmeticSelectors: List<String>
    ): String? {
        if (contentType.isBlank()) return null
        val context = TlsMitmSessionManager.requireContext()
        val host = normalizeAuthority(requestInspection?.host ?: session.host)
        val vendor = RuleRepository.classifyVendorFromHints(context, host, session.appName)
        val aggressiveNovelTarget = RuleRepository.shouldAggressivelyBlockForNovelApp(context, host, session.appName, vendor)
        val protectedNovelTarget = RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, host, requestInspection?.path, session.appName)
        if (contentType.contains("html") && cosmeticSelectors.isNotEmpty()) {
            return "neutralized-cosmetic-rule"
        }
        if (contentType.contains("text/html") || contentType.contains("json") || contentType.contains("javascript")) {
            val lowerBody = body.lowercase()
            val bodySignals = inspectAdBodySignals(lowerBody)
            if (bodySignals.score >= 3) {
                return "neutralized-body-strong-signal"
            }
            if (bodySignals.score >= 2 && protectedNovelTarget) {
                return "neutralized-body-novel-protected"
            }
            if (bodySignals.score >= 2 && aggressiveNovelTarget) {
                return "neutralized-body-novel-aggressive"
            }
            if (bodySignals.score >= 2 && isKnownAdVendor(vendor)) {
                return "neutralized-body-vendor-signal"
            }
        }
        return null
    }

    private fun buildCosmeticHtml(selectors: List<String>): String {
        if (selectors.isEmpty()) return "<html><body></body></html>"
        val css = selectors.joinToString(", ") { it }.take(4000)
        return "<html><head><style>$css { display: none !important; }</style></head><body></body></html>"
    }

    private val SCRIPTLET_INJECTION = """<script>
// AdGuard-like Scriptlets
(function(){
    try {
        window.open = function(){ return { closed: true }; };
        if(window.navigator && window.navigator.sendBeacon) {
            window.navigator.sendBeacon = function(){ return true; };
        }
    } catch(e){}
})();
</script>
<style>
/* Cosmetic Filters for common ad containers */
.ad-banner, .ad-container, .ads-wrapper, .ad-slot, .splash-ad, #adBanner, #adContainer, 
.adsbygoogle, .g-ad, .c-ad, .adbox, .ad-box, .ad_frame, .ad-area, #ads, .ad-content { display: none !important; }
</style>"""

    private fun buildSyntheticResponse(statusLine: String, contentType: String, body: String): String {
        val actualStatusLine = if (statusLine.startsWith("HTTP/1.")) {
            "${statusLine.substringBefore(' ')} 204 No Content"
        } else {
            "HTTP/1.1 204 No Content"
        }
        val injectedBody = if (contentType.contains("html")) {
            "<html><head>$SCRIPTLET_INJECTION</head><body></body></html>"
        } else body
        val contentLength = injectedBody.toByteArray(StandardCharsets.UTF_8).size
        return buildString {
            append(actualStatusLine).append("\r\n")
            append("Connection: close\r\n")
            append("Content-Type: ").append(if (contentType.isBlank()) "text/plain; charset=utf-8" else contentType).append("\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
            append("X-HanFeng-Block: 1\r\n")
            append("\r\n")
            append(injectedBody)
        }
    }

    private fun buildSyntheticResponse(statusLine: String, contentType: String, bodyBytes: ByteArray): ByteArray {
        val actualStatusLine = if (statusLine.startsWith("HTTP/1.")) {
            "${statusLine.substringBefore(' ')} 200 OK"
        } else {
            "HTTP/1.1 200 OK"
        }
        val contentTypeValue = if (contentType.isBlank()) "text/plain; charset=utf-8" else contentType
        val headerBytes = buildString {
            append(actualStatusLine).append("\r\n")
            append("Connection: close\r\n")
            append("Content-Type: ").append(contentTypeValue).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
            append("X-HanFeng-Block: 1\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        return headerBytes + bodyBytes
    }

    private fun decodeAscii(chunk: ByteArray): String? {
        return runCatching { String(chunk, StandardCharsets.ISO_8859_1) }.getOrNull()
    }

    private fun shouldPreferDeepInspection(
        host: String,
        path: String?,
        appName: String?,
        vendorHint: String? = null
    ): Boolean {
        val context = TlsMitmSessionManager.requireContext()
        val normalizedHost = normalizeAuthority(host)
        if (normalizedHost.isBlank()) return false
        if (RuleRepository.isWhitelistedDomain(normalizedHost)) return false
        if (RuleRepository.isBlocked(context, normalizedHost, appName = appName)) return true
        if (path != null && RuleRepository.isUrlBlocked(context, normalizedHost, path.lowercase(), appName)) return true
        val vendor = vendorHint?.takeIf { it.isNotBlank() }
            ?: RuleRepository.classifyVendorFromHints(context, normalizedHost, appName)
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(context, normalizedHost, appName, vendor)) {
            return true
        }
        val lowerPath = path?.lowercase().orEmpty()
        if (lowerPath.isBlank()) return false
        if (!looksLikeSuspiciousHttpPath(lowerPath)) return false
        return isKnownAdVendor(vendor)
    }

    private fun inspectAdBodySignals(lowerBody: String): BodySignalInspection {
        if (lowerBody.isBlank()) return BodySignalInspection(0, emptyList())
        val strongMatches = strongResponseAdKeywords.filter { keyword -> lowerBody.contains(keyword) }.distinct()
        val weakMatches = responseAdKeywords.filter { keyword -> lowerBody.contains(keyword) }.distinct()
        val reasons = mutableListOf<String>()
        var score = 0
        if (strongMatches.isNotEmpty()) {
            score += when {
                strongMatches.size >= 3 -> 4
                strongMatches.size == 2 -> 3
                else -> 2
            }
            reasons += strongMatches.take(4).map { "data-strong-keyword:$it" }
        }
        val trackingFieldHits = listOf(
            "\"imp\"",
            "\"impression\"",
            "\"impression_url\"",
            "\"impression_urls\"",
            "\"click_url\"",
            "\"clickurl\"",
            "\"click_track_url\"",
            "\"show_url\"",
            "\"showurl\"",
            "\"show_track_url\"",
            "\"track_url\"",
            "\"trackurl\"",
            "\"track_urls\"",
            "\"win_notice\"",
            "\"winnotice\"",
            "\"landing_page\"",
            "\"landingpage\"",
            "\"landing_url\"",
            "\"deep_link\"",
            "\"deeplink\"",
            "\"download_url\"",
            "\"downloadurl\"",
            "\"materialid\"",
            "\"material_id\"",
            "\"creativeid\"",
            "\"creative_id\"",
            "\"placementid\"",
            "\"placement_id\"",
            "\"slotid\"",
            "\"slot_id\"",
            "\"template_id\"",
            "\"templateid\"",
            "\"ecpm\"",
            "\"ecpm_level\"",
            "\"price_ratio\"",
            "\"request_id\"",
            "\"ad_source\"",
            "\"ad_info\"",
            "\"ad_infos\"",
            "\"ad_list\"",
            "\"adlist\"",
            "\"adstyle\"",
            "\"ad_type\"",
            "\"interaction_type\"",
            "\"image_url\"",
            "\"image_urls\"",
            "\"img_url\"",
            "\"video_url\"",
            "\"video_urls\"",
            "\"playable_url\"",
            "\"playable\"",
            "\"endcard_url\"",
            "\"endcard\"",
            "\"render_url\"",
            "\"monitor_url\"",
            "\"monitor_urls\"",
            "\"expo_url\"",
            "\"expo_urls\"",
            "\"landing_url\"",
            "\"callback_url\"",
            "\"target_url\"",
            "\"open_type\"",
            "\"open_screen\"",
            "\"startup\"",
            "\"app_name\"",
            "\"app_icon\"",
            "\"app_desc\"",
            "\"app_size\"",
            "\"download_type\"",
            "\"button_text\"",
            "\"btn_text\"",
            "\"desc_text\"",
            "\"title_text\"",
            "\"icon_url\"",
            "\"icon_urls\"",
            "\"img_list\"",
            "\"image_list\"",
            "\"materials\"",
            "\"material_list\"",
            "\"creatives\"",
            "\"creative_list\"",
            "\"reward_video\"",
            "\"rewardvideo\"",
            "\"fullscreen_video\"",
            "\"native_express\"",
            "\"landing_page_url\"",
            "\"download_button\"",
            "\"download_btn\""
        ).filter { token -> lowerBody.contains(token) }
        val novelAdFieldHits = listOf(
            "\"book_id\"",
            "\"book_name\"",
            "\"chapter_id\"",
            "\"chapter_name\"",
            "\"reader_type\"",
            "\"scene_id\"",
            "\"scene_type\"",
            "\"enter_from\"",
            "\"coin\"",
            "\"task_id\"",
            "\"task_type\"",
            "\"inspire\"",
            "\"excitation\"",
            "\"excitation_ad\"",
            "\"reward_amount\"",
            "\"unlock_style\"",
            "\"client_bidding\"",
            "\"unlock_chapter\"",
            "\"watch_ad\"",
            "\"watch_ad_unlock\"",
            "\"video_finish\"",
            "\"free_read\"",
            "\"reading_bonus\"",
            "\"welfare_page\"",
            "\"coin_reward\"",
            "\"sign_task\"",
            "\"task_reward\"",
            "\"ad_unlock\"",
            "\"ad_reward\"",
            "\"chapter_unlock\"",
            "\"chapter_reward\""
        ).filter { token -> lowerBody.contains(token) }
        if (trackingFieldHits.isNotEmpty()) {
            score += if (trackingFieldHits.size >= 2) 3 else 2
            reasons += trackingFieldHits.take(4).map { "data-field:$it" }
        }
        if (novelAdFieldHits.size >= 2 && (strongMatches.isNotEmpty() || trackingFieldHits.isNotEmpty())) {
            score += 2
            reasons += novelAdFieldHits.take(4).map { "novel-field:$it" }
        }
        if (weakMatches.size >= 3) {
            score += 2
            reasons += weakMatches.take(4).map { "data-keyword:$it" }
        } else if (weakMatches.size == 2 && strongMatches.isNotEmpty()) {
            score += 1
            reasons += weakMatches.take(2).map { "data-keyword:$it" }
        }
        val htmlMarkerHits = htmlAdMarkers.filter { marker -> lowerBody.contains(marker) }
        if (htmlMarkerHits.isNotEmpty()) {
            score += if (htmlMarkerHits.size >= 2) 2 else 1
            reasons += htmlMarkerHits.take(4).map { "html-marker:$it" }
        }
        return BodySignalInspection(score, reasons.distinct())
    }

    private fun isKnownAdVendor(vendor: String): Boolean {
        if (vendor.isBlank()) return false
        val normalized = vendor.trim().lowercase()
        return normalized != "未知" && normalized != "通用广告" && normalized != "generic_ad"
    }

    fun inspectHttp2Headers(
        session: TlsMitmSessionManager.TlsMitmSession,
        headers: List<HpackDecoder.HeaderField>
    ): Http2HeaderInspection? {
        if (headers.isEmpty()) return null
        val normalized = LinkedHashMap<String, MutableList<String>>()
        headers.forEach { header ->
            normalized.getOrPut(header.name.lowercase()) { mutableListOf() }.add(header.value)
        }
        val method = normalized[":method"]?.firstOrNull()?.ifBlank { null }
        val authority = normalized[":authority"]?.firstOrNull()?.ifBlank { null }
            ?.let(::normalizeAuthority)
            ?: normalized["host"]?.firstOrNull()?.ifBlank { null }
                ?.let(::normalizeAuthority)
            ?: normalizeAuthority(session.host)
        val path = normalized[":path"]?.firstOrNull()?.ifBlank { null }
        val scheme = normalized[":scheme"]?.firstOrNull()?.ifBlank { null }
        val status = normalized[":status"]?.firstOrNull()?.ifBlank { null }
        val contentType = normalized["content-type"]?.firstOrNull()?.ifBlank { null }
        val referer = normalized["referer"]?.firstOrNull()?.ifBlank { null }
        val userAgent = normalized["user-agent"]?.firstOrNull()?.ifBlank { null }
        val location = normalized["location"]?.firstOrNull()?.ifBlank { null }
        val setCookie = normalized["set-cookie"]?.firstOrNull()?.ifBlank { null }
        val lowerAuthority = normalizeAuthority(authority)
        val lowerPath = path?.lowercase().orEmpty()
        val lowerReferer = referer?.lowercase().orEmpty()
        val lowerContentType = contentType?.lowercase().orEmpty()
        val lowerLocation = location?.lowercase().orEmpty()
        val lowerSetCookie = setCookie?.lowercase().orEmpty()
        val lowerUserAgent = userAgent?.lowercase().orEmpty()
        // 白名单域名直接跳过检查，防止断网
        if (RuleRepository.isWhitelistedDomain(lowerAuthority)) return null
        var suspiciousScore = 0
        val reasons = mutableListOf<String>()
        val context = TlsMitmSessionManager.requireContext()
        if (RuleRepository.isBlocked(context, lowerAuthority, appName = session.appName)) {
            suspiciousScore += 3
            reasons += "blocked-host"
        }
        if (RuleRepository.isUrlBlocked(context, lowerAuthority, lowerPath, session.appName)) {
            suspiciousScore += 3
            reasons += "blocked-url"
        }
        if (RuleRepository.shouldAggressivelyBlockNovelProtectedUrl(context, lowerAuthority, lowerPath, session.appName)) {
            suspiciousScore += 3
            reasons += "novel-protected-path"
        }
        val vendor = RuleRepository.classifyVendorFromHints(context, lowerAuthority, session.appName)
        if (isKnownAdVendor(vendor)) {
            suspiciousScore += 1
            reasons += "vendor:$vendor"
        }
        if (RuleRepository.shouldAggressivelyBlockForNovelApp(context, lowerAuthority, session.appName, vendor)) {
            suspiciousScore += 2
            reasons += "novel-app-aggressive"
        }
        if (looksLikeSuspiciousHttpPath(lowerPath)) {
            suspiciousScore += 2
            reasons += "path-keyword"
        }
        if (suspiciousHeaderKeywords.any { lowerReferer.contains(it) }) {
            suspiciousScore += 1
            reasons += "referer-keyword"
        }
        if (suspiciousHeaderKeywords.any { lowerLocation.contains(it) }) {
            suspiciousScore += 1
            reasons += "location-keyword"
        }
        if (suspiciousHeaderKeywords.any { lowerSetCookie.contains(it) }) {
            suspiciousScore += 1
            reasons += "set-cookie-keyword"
        }
        if (strongResponseAdKeywords.any { lowerPath.contains(it) }) {
            suspiciousScore += 2
            reasons += "path-strong-keyword"
        }
        if (strongResponseAdKeywords.any { lowerLocation.contains(it) }) {
            suspiciousScore += 2
            reasons += "location-strong-keyword"
        }
        if (strongResponseAdKeywords.any { lowerSetCookie.contains(it) }) {
            suspiciousScore += 2
            reasons += "set-cookie-strong-keyword"
        }
        val headerTrackingHits = adTrackingHeaderFields.filter { field ->
            lowerLocation.contains(field) || lowerSetCookie.contains(field)
        }
        if (headerTrackingHits.isNotEmpty()) {
            suspiciousScore += if (headerTrackingHits.size >= 2) 3 else 2
            reasons += "header-tracking"
        }
        if (strongResponseAdKeywords.any { lowerContentType.contains(it) }) {
            suspiciousScore += 1
            reasons += "content-type-keyword"
        }
        if (responseAdKeywords.any { lowerContentType.contains(it) }) {
            suspiciousScore += 1
            reasons += "content-type-weak-keyword"
        }
        return Http2HeaderInspection(
            method = method,
            authority = lowerAuthority,
            path = path,
            scheme = scheme,
            status = status,
            contentType = contentType,
            referer = referer,
            userAgent = userAgent,
            location = location,
            setCookie = setCookie,
            vendor = vendor,
            suspiciousScore = suspiciousScore,
            suspiciousReasons = reasons,
            requestLike = method != null && status == null,
            responseLike = status != null
        )
    }

    fun decideHttp2Action(inspection: Http2HeaderInspection): Http2ActionDecision {
        if (inspection.suspiciousScore < HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE) {
            return Http2ActionDecision(
                action = "allow",
                confidence = "high",
                shouldBlockCandidate = false,
                shouldSyntheticRespond = false
            )
        }
        val shouldBlock = shouldBlockHttp2ResponseFromHeaders(inspection)
        return Http2ActionDecision(
            action = if (shouldBlock) "block" else "monitor",
            confidence = if (inspection.suspiciousScore >= 4) "high" else "medium",
            shouldBlockCandidate = shouldBlock,
            shouldSyntheticRespond = shouldBlock && inspection.responseLike
        )
    }

    private fun shouldBlockHttp2ResponseFromHeaders(inspection: Http2HeaderInspection): Boolean {
        if (inspection.suspiciousScore < HTTP2_RESPONSE_BLOCK_CANDIDATE_SCORE) return false
        if (inspection.suspiciousScore >= 4) return true
        val reasons = inspection.suspiciousReasons.toSet()
        if (reasons.any { reason ->
                reason == "blocked-host" ||
                    reason == "novel-app-aggressive" ||
                    reason.startsWith("header-field:") ||
                    reason == "path-strong-keyword" ||
                    reason == "location-strong-keyword" ||
                    reason == "set-cookie-strong-keyword"
            }
        ) {
            return true
        }
        return inspection.suspiciousScore >= 3 &&
            reasons.any { it == "path-keyword" || it == "location-keyword" || it == "set-cookie-keyword" } &&
            reasons.any { it.startsWith("vendor:") }
    }

    private fun normalizeAuthority(value: String): String {
        val trimmed = value.trim().lowercase().trimEnd('.')
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith('[')) {
            val endBracket = trimmed.indexOf(']')
            if (endBracket > 1) {
                return trimmed.substring(1, endBracket)
            }
        }
        val firstColon = trimmed.indexOf(':')
        val lastColon = trimmed.lastIndexOf(':')
        if (firstColon > 0 && firstColon == lastColon) {
            val port = trimmed.substring(lastColon + 1)
            if (port.isNotEmpty() && port.all(Char::isDigit)) {
                return trimmed.substring(0, lastColon)
            }
        }
        return trimmed
    }

    private fun looksLikeSuspiciousHttpPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (suspiciousPathKeywords.any { path.contains(it) }) return true
        val query = path.substringAfter('?', "")
        if (query.isBlank()) return false
        return suspiciousQueryKeywords.any { keyword ->
            query.contains("$keyword=") || query.contains("_$keyword=") || query.contains("-$keyword=") || query.contains(keyword)
        }
    }

    private fun rewriteRequestLine(requestLine: String, removeParams: Set<String>): String {
        if (removeParams.isEmpty()) return requestLine
        val parts = requestLine.split(' ')
        if (parts.size < 2) return requestLine
        val updatedPath = rewritePathOnly(parts[1], removeParams)
        if (updatedPath == parts[1]) return requestLine
        return buildString {
            append(parts[0]).append(' ').append(updatedPath)
            if (parts.size > 2) append(' ').append(parts.drop(2).joinToString(" "))
        }
    }

    private fun rewritePathOnly(path: String, removeParams: Set<String>): String {
        if (removeParams.isEmpty() || !path.contains('?')) return path
        val base = path.substringBefore('?')
        val fragment = path.substringAfter('#', "")
        val query = path.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return path
        val filtered = query.split('&')
            .filter { it.isNotBlank() }
            .filterNot { part -> removeParams.contains(part.substringBefore('=').trim().lowercase()) }
        val rebuilt = buildString {
            append(base)
            if (filtered.isNotEmpty()) append('?').append(filtered.joinToString("&"))
            if (fragment.isNotBlank()) append('#').append(fragment)
        }
        return rebuilt
    }

    sealed interface FilterResult {
        data class PassThrough(val payload: ByteArray, val reason: String) : FilterResult
        data class Replaced(val payload: ByteArray, val reason: String, val originalBytes: Int = 0) : FilterResult
    }

    data class RequestInspection(
        val method: String,
        val path: String,
        val host: String,
        val httpVersion: String
    )

    data class Http2HeaderInspection(
        val method: String?,
        val authority: String,
        val path: String?,
        val scheme: String?,
        val status: String?,
        val contentType: String?,
        val referer: String?,
        val userAgent: String?,
        val location: String?,
        val setCookie: String?,
        val vendor: String,
        val suspiciousScore: Int,
        val suspiciousReasons: List<String>,
        val requestLike: Boolean,
        val responseLike: Boolean
    )

    data class Http2ActionDecision(
        val action: String,
        val confidence: String,
        val shouldBlockCandidate: Boolean,
        val shouldSyntheticRespond: Boolean = false
    )

    data class Http2HeaderRewriteResult(
        val headers: List<HpackDecoder.HeaderField>,
        val changed: Boolean
    )

    sealed interface BufferedHttp1Result {
        data object AwaitMore : BufferedHttp1Result
        data class Ready(val responseBytes: ByteArray, val remainderBytes: ByteArray) : BufferedHttp1Result
        data class Bypass(val reason: String) : BufferedHttp1Result
    }

    data class Http2DataInspection(
        val suspiciousScore: Int,
        val suspiciousReasons: List<String>,
        val confidence: String,
        val samplePreview: String,
        val vendor: String,
        val combinedSample: ByteArray
    )

    private data class BodySignalInspection(
        val score: Int,
        val reasons: List<String>
    )
}
