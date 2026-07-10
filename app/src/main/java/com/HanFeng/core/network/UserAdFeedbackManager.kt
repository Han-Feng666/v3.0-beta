package com.HanFeng.core.network

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.HanFeng.ui.showSafely
import com.google.gson.Gson
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.data.LogRepository
import com.HanFeng.data.RuleRepository
import com.HanFeng.model.PendingFeedbackRule
import com.HanFeng.model.RuleSource
import com.HanFeng.model.TrainingSample
import com.HanFeng.model.UserAdFeedbackSample
import java.io.File
import java.io.FileWriter
import java.util.UUID

object UserAdFeedbackManager {
    private const val FEEDBACK_FILE = "ad_feedback_samples.jsonl"
    private const val WINDOW_MILLIS = 60_000L
    private const val MAX_EVENTS = 256
    private const val MAX_FEEDBACK_FILE_BYTES = 50L * 1024 * 1024
    private val gson = Gson()
    private val lock = Any()
    private val events = ArrayDeque<NetworkActivity>()

    data class NetworkActivity(
        val appName: String,
        val packageName: String? = null,
        val host: String? = null,
        val path: String? = null,
        val sni: String? = null,
        val ip: String? = null,
        val protocol: String,
        val source: String,
        val score: Int = 0,
        val learningCandidate: Boolean = false,
        val capturedAt: Long = System.currentTimeMillis()
    )

    fun recordNetworkActivity(context: Context, activity: NetworkActivity) {
        val normalized = normalize(activity) ?: return
        if (isProtected(normalized.host ?: normalized.sni)) return
        synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            events.addLast(normalized)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    fun captureFeedbackSample(context: Context): UserAdFeedbackSample? {
        val now = System.currentTimeMillis()
        val selected = synchronized(lock) {
            pruneLocked(now)
            events.maxWithOrNull(
                compareBy<NetworkActivity> { eventScore(it) }
                    .thenBy { it.capturedAt }
            )
        }
        if (selected == null) {
            LogRepository.append(context, "User ad feedback skipped: no recent network activity")
            return null
        }
        val sample = UserAdFeedbackSample(
            appName = selected.appName,
            packageName = selected.packageName,
            host = selected.host,
            path = selected.path,
            sni = selected.sni,
            ip = selected.ip,
            protocol = selected.protocol,
            source = "user_feedback",
            capturedAt = now
        )
        appendJsonLine(context, FEEDBACK_FILE, sample)
        TrainingSampleExporter.appendFromFeedback(context, sample)
        buildPendingRule(sample)?.let { pending ->
            FeatureSettingsRepository.addPendingFeedbackRule(context, pending)
            LogRepository.append(context, "User ad feedback captured rule=${pending.ruleText} app=${sample.appName} host=${sample.host ?: sample.sni ?: "none"} path=${sample.path ?: "none"}")
        } ?: LogRepository.append(context, "User ad feedback captured without rule app=${sample.appName} ip=${sample.ip ?: "none"}")
        return sample
    }

    fun confirmPendingRule(context: Context, pendingRuleId: String): Boolean {
        val pending = FeatureSettingsRepository.getPendingFeedbackRules(context)
            .firstOrNull { it.id == pendingRuleId }
            ?: return false
        val added = RuleRepository.addRules(
            context = context,
            rawInput = pending.ruleText,
            source = RuleSource.MANUAL,
            allowWhitelistDomains = true
        )
        FeatureSettingsRepository.removePendingFeedbackRule(context, pendingRuleId)
        LogRepository.append(context, "User ad feedback rule confirmed rule=${pending.ruleText} added=${added.size}")
        return added.isNotEmpty()
    }

    fun showPendingRuleDialog(activity: Activity) {
        val rules = FeatureSettingsRepository.getPendingFeedbackRules(activity)
        if (rules.isEmpty()) {
            LogRepository.append(activity, "User ad feedback pending rule dialog skipped: empty")
            return
        }
        val labels = rules.map { "${it.ruleText}\n${it.appName}" }.toTypedArray()
        if (activity.isFinishing || activity.isDestroyed) {
            LogRepository.append(activity, "User ad feedback pending rule dialog skipped: activity unavailable")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("待确认广告规则")
            .setItems(labels) { _, which ->
                confirmPendingRule(activity, rules[which].id)
            }
            .setNegativeButton("取消", null)
            .showSafely(activity, "Show pending feedback rule dialog failed")
    }

    fun feedbackFile(context: Context): File = File(context.filesDir, FEEDBACK_FILE)

    private fun normalize(activity: NetworkActivity): NetworkActivity? {
        val host = normalizeHost(activity.host)
        val sni = normalizeHost(activity.sni)
        val path = activity.path?.takeIf { it.startsWith("/") }?.take(180)
        if (host == null && sni == null && activity.ip.isNullOrBlank()) return null
        return activity.copy(host = host, sni = sni, path = path, protocol = activity.protocol.uppercase())
    }

    private fun normalizeHost(value: String?): String? {
        return value
            ?.trim()
            ?.trim('.')
            ?.lowercase()
            ?.takeIf { it.contains('.') && it.length <= 253 }
    }

    private fun isProtected(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return RuleRepository.isWhitelistedDomain(host) ||
            RuleRepository.isBusinessCoreDomain(host) ||
            RuleRepository.isSocialCoreDomain(host) ||
            RuleRepository.isGameCoreDomain(host) ||
            RuleRepository.shouldProtectMediaTraffic(host)
    }

    private fun eventScore(activity: NetworkActivity): Int {
        var score = activity.score
        val host = activity.host ?: activity.sni.orEmpty()
        val path = activity.path.orEmpty().lowercase()
        if (activity.learningCandidate) score += 8
        if (RuleRepository.looksLikeAdDomain(host)) score += 5
        if (RuleRepository.looksLikeAdSdkInfraDomain(host, "")) score += 4
        if (activity.protocol.equals("QUIC", ignoreCase = true)) score += 2
        if (path.contains("ad") || path.contains("ads") || path.contains("banner") || path.contains("splash") || path.contains("reward")) score += 3
        return score
    }

    private fun buildPendingRule(sample: UserAdFeedbackSample): PendingFeedbackRule? {
        val host = sample.host ?: sample.sni ?: return null
        if (isProtected(host)) return null
        val ruleText = if (!sample.path.isNullOrBlank() && looksSpecificAdPath(sample.path)) {
            "||$host${sample.path}"
        } else {
            "||$host^"
        }
        return PendingFeedbackRule(
            id = UUID.randomUUID().toString(),
            ruleText = ruleText,
            host = host,
            path = sample.path,
            appName = sample.appName,
            source = sample.source,
            createdAt = sample.capturedAt
        )
    }

    private fun looksSpecificAdPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("/ad") || lower.contains("ads") || lower.contains("banner") || lower.contains("splash") || lower.contains("reward")
    }

    private fun pruneLocked(now: Long) {
        while (events.isNotEmpty() && now - events.first().capturedAt > WINDOW_MILLIS) {
            events.removeFirst()
        }
    }

    private fun appendJsonLine(context: Context, fileName: String, value: Any) {
        runCatching {
            val file = File(context.filesDir, fileName)
            if (file.exists() && file.length() >= MAX_FEEDBACK_FILE_BYTES) return@runCatching
            FileWriter(file, true).use { writer ->
                writer.append(gson.toJson(value)).append('\n')
            }
        }.onFailure {
            LogRepository.append(context, "Append feedback JSONL failed file=$fileName error=${it.message ?: it.javaClass.simpleName}")
        }
    }
}
