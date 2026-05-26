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
    val redirect: Boolean = false,
    val domainConstraints: Set<String> = emptySet(),
    val denyallow: Set<String> = emptySet(),
    val urlblock: Boolean = false,
    val appPackages: Set<String> = emptySet(),
    val keywordPattern: String? = null,
    val pathPattern: String? = null,
    val regexPattern: String? = null,
    val cosmeticSelector: String? = null,
    val removeParams: Set<String> = emptySet(),
    val cspValue: String? = null
)

enum class RuleSource(val label: String) {
    MANUAL("手动"),
    IMPORTED("导入"),
    REFERENCE("参考"),
    UNSUPPORTED("暂不支持")
}

data class DashboardStats(
    val todayBlocked: Int,
    val totalBlocked: Int,
    val dnsBlocked: Int,
    val httpBlocked: Int,
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

data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val whitelisted: Boolean
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
