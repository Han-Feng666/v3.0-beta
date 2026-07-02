package com.HanFeng.model

import android.graphics.drawable.Drawable

data class BlockRule(
    val id: String,
    val domain: String,
    val vendor: String,
    val source: RuleSource,
    val dnsTypes: Set<Int>? = null,
    val excludedDnsTypes: Set<Int>? = null,
    val thirdParty: Boolean = false,
    val firstParty: Boolean = false,
    val important: Boolean = false,
    val redirect: Boolean = false,
    val domainConstraints: Set<String>? = null,
    val excludedDomainConstraints: Set<String> = emptySet(),
    val denyallow: Set<String> = emptySet(),
    val urlblock: Boolean = false,
    val requestTypes: Set<String> = emptySet(),
    val appPackages: Set<String> = emptySet(),
    val destinationPorts: Set<Int> = emptySet(),
    val sourcePorts: Set<Int> = emptySet(),
    val keywordPattern: String? = null,
    val pathPattern: String? = null,
    val ipCidr: String? = null,
    val regexPattern: String? = null,
    val cosmeticSelector: String? = null,
    val cosmeticException: Boolean = false,
    val exceptionRule: Boolean = false,
    val removeParams: Set<String> = emptySet(),
    val removeParamRegexes: Set<String> = emptySet(),
    val removeRequestHeaders: Set<String> = emptySet(),
    val setRequestHeaders: Set<String> = emptySet(),
    val replaceRules: Set<String> = emptySet(),
    val cspValue: String? = null,
    val redirectResource: String? = null,
    val jsInjectRules: Set<String> = emptySet(),
    val cookieRemove: Set<String> = emptySet(),
    val cookieSet: Set<String> = emptySet(),
    val toDomains: Set<String> = emptySet(),
    val cname: Boolean = false,
    val emptyResponse: Boolean = false,
    val genericblock: Boolean = false,
    val specifichide: Boolean = false,
    val generichide: Boolean = false,
    val dnsrewrite: String? = null,
    val fromDomains: Set<String> = emptySet(),
    val excludedFromDomains: Set<String> = emptySet(),
    val network: Boolean = false,
    val blockIpv6: Boolean = false,
    val blockIpv4: Boolean = false,
    val ctags: Set<String> = emptySet(),
    val generichideException: Boolean = false,
    val remoteSourceId: String? = null
)

enum class RuleSource(val label: String) {
    MANUAL("手动"),
    IMPORTED("导入"),
    REFERENCE("参考"),
    UNSUPPORTED("暂不支持")
}

data class RemoteRuleSourceConfig(
    val id: String,
    val name: String,
    val url: String,
    val authorId: String? = null,
    val enabled: Boolean = true,
    val lastUpdatedAt: Long = 0L,
    val lastRuleCount: Int = 0,
    val lastError: String? = null
)

data class DashboardStats(
    val todayBlocked: Int,
    val totalBlocked: Int,
    val dnsBlocked: Int,
    val mitmBlocked: Int,
    val requestTotal: Int,
    val responseTotal: Int,
    val bytesSaved: Long
)

data class RankingEntry(
    val name: String,
    val value: Int
)

enum class RankingType {
    VENDOR_BLOCKED,
    VENDOR_REQUEST,
    VENDOR_RESPONSE,
    APP_BLOCKED,
    APP_REQUEST,
    APP_RESPONSE
}

data class RankingBundle(
    val vendorBlocked: List<RankingEntry>,
    val vendorRequest: List<RankingEntry>,
    val vendorResponse: List<RankingEntry>,
    val appBlocked: List<RankingEntry>,
    val appRequest: List<RankingEntry>,
    val appResponse: List<RankingEntry>
)

data class UserAdFeedbackSample(
    val appName: String,
    val packageName: String?,
    val host: String?,
    val path: String?,
    val sni: String?,
    val ip: String?,
    val protocol: String,
    val source: String = "user_feedback",
    val capturedAt: Long = System.currentTimeMillis()
)

data class PendingFeedbackRule(
    val id: String,
    val ruleText: String,
    val host: String?,
    val path: String?,
    val appName: String,
    val source: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class TrainingSample(
    val host: String?,
    val path: String?,
    val queryKeys: List<String> = emptyList(),
    val contentType: String? = null,
    val sampleJsonKeys: List<String> = emptyList(),
    val payloadLength: Int = 0,
    val statusCode: Int? = null,
    val protocol: String,
    val port: Int? = null,
    val appCategory: String = "ordinary",
    val isQuic: Boolean = false,
    val isHttpdns: Boolean = false,
    val hitAdToken: Boolean = false,
    val label: String = "unlabeled",
    val capturedAt: Long = System.currentTimeMillis()
)

data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val whitelisted: Boolean,
    val coexistSelected: Boolean = false,
    val coexistRecommended: Boolean = false
)

data class LocalProxyCoexistConfig(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int? = null,
    val controllerPackageName: String? = null,
    val remarks: String? = null,
    val detectedAppLabel: String? = null,
    val detectedPackageName: String? = null,
    val detectionSource: String? = null
)

data class LocalProxySuggestion(
    val appLabel: String,
    val packageName: String,
    val host: String,
    val port: Int,
    val reason: String
)

data class DnsQuestion(
    val id: Int,
    val domain: String,
    val qType: Int,
    val timestamp: Long
)

data class DnsAnswer(
    val name: String,
    val type: Int,
    val ttl: Int,
    val data: String
)

data class DnsResponse(
    val id: Int,
    val questions: List<DnsQuestion>,
    val answers: List<DnsAnswer>,
    val isBlocked: Boolean = false,
    val blockRule: BlockRule? = null
)

data class LogEntry(
    val id: Long = 0,
    val timestamp: Long,
    val domain: String,
    val vendor: String,
    val appName: String,
    val isBlocked: Boolean,
    val reason: String = ""
)

data class PacketInfo(
    val version: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val protocol: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
    val tcpSequenceNumber: Long? = null,
    val tcpAcknowledgementNumber: Long? = null,
    val tcpFlags: Int = 0,
    val tcpWindowSize: Int? = null,
    val appName: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketInfo) return false
        return version == other.version &&
            sourceAddress.contentEquals(other.sourceAddress) &&
            destinationAddress.contentEquals(other.destinationAddress) &&
            protocol == other.protocol &&
            sourcePort == other.sourcePort &&
            destinationPort == other.destinationPort &&
            payload.contentEquals(other.payload) &&
            tcpSequenceNumber == other.tcpSequenceNumber &&
            tcpAcknowledgementNumber == other.tcpAcknowledgementNumber &&
            tcpFlags == other.tcpFlags &&
            tcpWindowSize == other.tcpWindowSize &&
            appName == other.appName
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + sourceAddress.contentHashCode()
        result = 31 * result + destinationAddress.contentHashCode()
        result = 31 * result + protocol
        result = 31 * result + sourcePort
        result = 31 * result + destinationPort
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (tcpSequenceNumber?.hashCode() ?: 0)
        result = 31 * result + (tcpAcknowledgementNumber?.hashCode() ?: 0)
        result = 31 * result + tcpFlags
        result = 31 * result + (tcpWindowSize?.hashCode() ?: 0)
        result = 31 * result + (appName?.hashCode() ?: 0)
        return result
    }
}

sealed interface RuleListItem {
    data class Group(
        val vendor: String,
        val count: Int,
        val expanded: Boolean
    ) : RuleListItem

    data class Domain(
        val rule: BlockRule,
        val selected: Boolean,
        val selectionMode: Boolean
    ) : RuleListItem

    data class More(
        val vendor: String,
        val remainingCount: Int
    ) : RuleListItem
}
