package com.HanFeng.core.network

import android.content.Context
import com.google.gson.Gson
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.model.TrainingSample
import com.HanFeng.model.UserAdFeedbackSample
import java.io.File
import java.io.FileWriter

object TrainingSampleExporter {
    private const val TRAINING_FILE = "training_samples.jsonl"
    private const val MAX_TRAINING_FILE_BYTES = 50L * 1024 * 1024
    private val gson = Gson()
    private val queryKeyRegex = Regex("[?&]([^=&?#]+)=?")
    private val jsonKeyRegex = Regex("\\\"([^\\\"]{1,48})\\\"\\s*:")

    fun appendUnknownVendorSample(
        context: Context,
        host: String,
        path: String? = null,
        contentType: String? = null,
        payloadPreview: String? = null,
        payloadLength: Int = 0,
        statusCode: Int? = null,
        protocol: String = "DNS",
        port: Int? = null,
        appName: String? = null,
        isHttpdns: Boolean = false,
        hitAdToken: Boolean = false,
        label: String = "unlabeled"
    ) {
        val normalizedHost = host.trim().trim('.').lowercase().takeIf { it.contains('.') } ?: return
        append(
            context,
            TrainingSample(
                host = normalizedHost,
                path = path?.take(180),
                queryKeys = extractQueryKeys(path),
                contentType = contentType?.take(80),
                sampleJsonKeys = extractJsonKeys(payloadPreview),
                payloadLength = payloadLength.coerceAtLeast(0),
                statusCode = statusCode,
                protocol = protocol.uppercase(),
                port = port,
                appCategory = appCategory(appName),
                isQuic = protocol.equals("QUIC", ignoreCase = true),
                isHttpdns = isHttpdns,
                hitAdToken = hitAdToken || RuleRepository.looksLikeAdDomain(normalizedHost) || RuleRepository.looksLikeAdSdkInfraDomain(normalizedHost, ""),
                label = label
            )
        )
    }

    fun appendFromFeedback(context: Context, sample: UserAdFeedbackSample) {
        appendUnknownVendorSample(
            context = context,
            host = sample.host ?: sample.sni ?: return,
            path = sample.path,
            protocol = sample.protocol,
            appName = sample.appName,
            hitAdToken = true,
            label = "ad"
        )
    }

    fun appendContentLabel(context: Context, host: String, path: String? = null, appName: String? = null) {
        appendUnknownVendorSample(
            context = context,
            host = host,
            path = path,
            protocol = "HTTPS",
            appName = appName,
            hitAdToken = false,
            label = "content"
        )
    }

    fun trainingFile(context: Context): File = File(context.filesDir, TRAINING_FILE)

    private fun append(context: Context, sample: TrainingSample) {
        runCatching {
            val file = trainingFile(context)
            if (file.exists() && file.length() >= MAX_TRAINING_FILE_BYTES) return@runCatching
            FileWriter(file, true).use { writer ->
                writer.append(gson.toJson(sample)).append('\n')
            }
        }.onFailure {
            LogRepository.append(context, "Append training JSONL failed error=${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun extractQueryKeys(path: String?): List<String> {
        if (path.isNullOrBlank()) return emptyList()
        return queryKeyRegex.findAll(path)
            .map { it.groupValues[1].lowercase().take(48) }
            .distinct()
            .take(16)
            .toList()
    }

    private fun extractJsonKeys(payloadPreview: String?): List<String> {
        if (payloadPreview.isNullOrBlank()) return emptyList()
        return jsonKeyRegex.findAll(payloadPreview)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .take(16)
            .toList()
    }

    private fun appCategory(appName: String?): String {
        val normalized = appName.orEmpty()
        return when {
            RuleRepository.isAggressiveAdAppHint(normalized) -> "high_risk"
            RuleRepository.isNovelAppHint(normalized) -> "high_risk"
            normalized.contains("微信") || normalized.contains("支付") || normalized.contains("银行") -> "protected"
            else -> "ordinary"
        }
    }
}
