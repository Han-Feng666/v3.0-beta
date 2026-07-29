package com.HanFeng.service

sealed interface FilterResult {
    data class PassThrough(val payload: ByteArray, val reason: String, val ruleDebug: List<String> = emptyList()) : FilterResult
    data class Replaced(val payload: ByteArray, val reason: String, val originalBytes: Int = 0, val ruleDebug: List<String> = emptyList()) : FilterResult
}

data class RequestInspection(
    val method: String,
    val path: String,
    val host: String,
    val httpVersion: String,
    val referer: String?,
    val origin: String?,
    val upgrade: String? = null,
    val requestHeaders: Map<String, String> = emptyMap()
) {
    val isWebSocket: Boolean get() = upgrade.equals("websocket", ignoreCase = true)
}

data class Http2HeaderInspection(
    val method: String?,
    val authority: String,
    val appName: String?,
    val path: String?,
    val scheme: String?,
    val status: String?,
    val protocol: String? = null,
    val contentType: String?,
    val contentEncoding: String? = null,
    val referer: String?,
    val userAgent: String?,
    val location: String?,
    val setCookie: String?,
    val vendor: String,
    val suspiciousScore: Int,
    val suspiciousReasons: List<String>,
    val redirectResource: String? = null,
    val cspValue: String? = null,
    val requestLike: Boolean,
    val responseLike: Boolean,
    val hasBodyRewriteDirectives: Boolean = false
) {
    val isWebSocket: Boolean get() = method.equals("CONNECT", ignoreCase = true) &&
        protocol.equals("websocket", ignoreCase = true)
}

data class Http2ActionDecision(
    val action: String,
    val confidence: String,
    val shouldBlockCandidate: Boolean,
    val shouldSyntheticRespond: Boolean = false,
    val redirectResource: String? = null,
    val cspValue: String? = null,
    val contentType: String? = null
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
    val combinedSample: ByteArray,
    val redirectResource: String? = null,
    val cspValue: String? = null,
    val contentType: String = "",
    val replacementBody: ByteArray? = null,
    val replacementContentType: String = "",
    val rewriteReason: String? = null
)
