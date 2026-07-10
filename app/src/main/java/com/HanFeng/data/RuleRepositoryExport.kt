package com.HanFeng.data

import android.content.Context
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RuleSource
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则导出扩展功能
 */
object RuleRepositoryExport {
    
    /**
     * 导出规则到 txt 文件
     */
    fun exportRulesToTxt(
        context: Context,
        outputFile: File,
        includeWhitelist: Boolean = false,
        includeSmartScored: Boolean = false
    ): Int {
        val exported = buildRulesText(
            context = context,
            includeWhitelist = includeWhitelist,
            includeSmartScored = includeSmartScored
        )
        outputFile.writeText(exported.content)
        return exported.count
    }

    fun buildRulesText(
        context: Context,
        includeWhitelist: Boolean = false,
        includeSmartScored: Boolean = false
    ): ExportedRules {
        val rules = RuleRepository.getRules(context)
        
        val filteredRules = rules.filter { rule ->
            if (!includeSmartScored && rule.id.startsWith("smart-score-")) return@filter false
            if (!includeWhitelist && (rule.domain.startsWith("@@") || rule.id.contains("whitelist"))) return@filter false
            true
        }
        
        if (filteredRules.isEmpty()) {
            return ExportedRules("# 没有可导出的规则\n", 0)
        }
        
        var exportedCount = 0
        val content = buildString {
            appendLine("! Title: 寒枫广告 blocking 规则导出")
            appendLine("! Description: 从寒枫 App 导出的自定义广告拦截规则")
            appendLine("! Version: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("!")
            appendLine("! 导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
            appendLine("! 规则总数：${filteredRules.size}")
            appendLine("! 来源：寒枫 App 本地规则库")
            appendLine("!")
            appendLine("! 使用说明：")
            appendLine("! 1. 可在 AdGuard、AdGuard Home、Pi-hole 等工具中使用")
            appendLine("! 2. 部分寒枫特有功能（如 app= 限定）可能不被其他工具支持")
            appendLine("! 3. 如导致 App 功能异常，请将相关域名加入白名单")
            appendLine("!")
            appendLine("")
            
            val rulesByVendor = filteredRules.groupBy { it.vendor }
                .toList()
                .sortedBy { (vendor, _) ->
                    when (vendor) {
                        "通用广告/追踪 (Generic Ad/Tracking)" -> 0
                        "其它 (Other)" -> 1
                        else -> 2
                    }
            }
            
            for ((vendor, vendorRules) in rulesByVendor) {
                appendLine("")
                appendLine("! ===========================================================================")
                appendLine("! ${vendor}")
                appendLine("! ===========================================================================")
                appendLine("")
                
                for (rule in vendorRules.sortedBy { it.domain }) {
                    val ruleLine = buildRuleLine(rule)
                    appendLine(ruleLine)
                    exportedCount++
                }
            }
            
            appendLine("")
            appendLine("! ===========================================================================")
            appendLine("! 导出完成")
            appendLine("! 总计：${exportedCount} 条规则")
            appendLine("! ===========================================================================")
        }
        
        return ExportedRules(content, exportedCount)
    }

    data class ExportedRules(val content: String, val count: Int)
    
    private fun buildRuleLine(rule: BlockRule): String {
        val parts = mutableListOf<String>()
        parts += rule.domain
        
        rule.dnsTypes?.let { dnsTypes ->
            if (dnsTypes.isNotEmpty()) parts += "\$dns-type=${dnsTypes.joinToString("|")}"
        }
        
        rule.excludedDnsTypes?.let { excludedTypes ->
            if (excludedTypes.isNotEmpty()) parts += "\$dns-exclude-type=${excludedTypes.joinToString("|")}"
        }
        
        if (rule.thirdParty) parts += "\$third-party"
        if (rule.firstParty) parts += "\$first-party"
        
        val domainScope = buildList {
            rule.domainConstraints?.let { constraints -> addAll(constraints) }
            addAll(rule.excludedDomainConstraints.map { "~$it" })
        }
        if (domainScope.isNotEmpty()) {
            parts += "\$domain=${domainScope.joinToString("|")}"
        }

        rule.requestTypes.sorted().forEach { requestType ->
            parts += "\$$requestType"
        }
        
        if (rule.denyallow.isNotEmpty()) parts += "\$denyallow=${rule.denyallow.joinToString("|")}"
        if (rule.urlblock) parts += "\$url"
        
        if (rule.appPackages.isNotEmpty()) parts += "\$app=${rule.appPackages.joinToString("|")}"
        
        if (rule.destinationPorts.isNotEmpty()) {
            parts += "\$destination-port=${rule.destinationPorts.joinToString(",")}"
        }
        if (rule.sourcePorts.isNotEmpty()) {
            parts += "\$source-port=${rule.sourcePorts.joinToString(",")}"
        }
        
        rule.pathPattern?.let { path -> parts += "\$path=$path" }
        rule.keywordPattern?.let { keyword -> parts += "\$keyword=$keyword" }
        rule.ipCidr?.let { cidr -> parts += "\$ip-cidr=$cidr" }
        rule.regexPattern?.let { regex -> parts += "\$regex=$regex" }
        rule.cosmeticSelector?.let { selector -> parts += "\$ cosmetic=$selector" }
        
        rule.cspValue?.let { csp -> parts += "\$csp=$csp" }
        
        if (rule.redirect) {
            rule.redirectResource?.let { resource -> parts += "\$redirect=$resource" }
        }
        
        val allRemoveParams = buildString {
            append(rule.removeParams.joinToString(","))
            if (rule.removeParamRegexes.isNotEmpty()) {
                if (isNotEmpty()) append(",")
                append("pattern:${rule.removeParamRegexes.joinToString(",")}")
            }
        }
        if (allRemoveParams.isNotEmpty()) parts += "\$removeparam=$allRemoveParams"
        
        if (rule.removeRequestHeaders.isNotEmpty()) {
            parts += "\$removeheader=request:${rule.removeRequestHeaders.joinToString(",")}"
        }
        if (rule.setRequestHeaders.isNotEmpty()) {
            parts += "\$setheader=request:${rule.setRequestHeaders.joinToString(",")}"
        }
        if (rule.replaceRules.isNotEmpty()) {
            parts += "\$replace=${rule.replaceRules.joinToString("||")}"
        }
        
        return parts.joinToString(" ")
    }
    
    fun exportRulesAsDomainList(context: Context, outputFile: File): Int {
        val domains = RuleRepository.getRules(context)
            .filter { !it.id.startsWith("smart-score-") }
            .filter { !it.domain.startsWith("@@") }
            .map { it.domain }
            .distinct()
            .sorted()
        
        if (domains.isEmpty()) {
            outputFile.writeText("# 没有可导出的域名\n")
            return 0
        }
        
        BufferedWriter(FileWriter(outputFile)).use { writer ->
            writer.writeLine("# 寒枫广告 blocking 规则 - 纯域名列表")
            writer.writeLine("# 导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
            writer.writeLine("# 域名总数：${domains.size}")
            writer.writeLine("#")
            writer.writeLine("# 使用说明：")
            writer.writeLine("# 1. 可在 hosts 文件中使用，格式为：0.0.0.0 domain.com")
            writer.writeLine("# 2. 可在 AdGuard 等工具中直接导入")
            writer.writeLine("#")
            writer.writeLine("")
            
            domains.forEach { domain ->
                writer.writeLine(domain)
            }
        }
        
        return domains.size
    }
    
    private fun BufferedWriter.writeLine(line: String) {
        write(line)
        newLine()
    }
}
