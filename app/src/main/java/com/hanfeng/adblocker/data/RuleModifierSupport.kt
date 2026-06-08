package com.HanFeng.data

object RuleModifierSupport {
    sealed interface RemoveParamToken {
        data class Exact(val name: String) : RemoveParamToken
        data class Regex(val pattern: String) : RemoveParamToken
    }

    data class ModifierInfo(
        val dnsTypes: Set<Int>? = null,
        val excludedDnsTypes: Set<Int>? = null,
        val badfilter: Boolean = false,
        val appScoped: Boolean = false,
        val appPackages: Set<String> = emptySet(),
        val destinationPorts: Set<Int> = emptySet(),
        val sourcePorts: Set<Int> = emptySet(),
        val domainScoped: Boolean = false,
        val thirdParty: Boolean = false,
        val firstParty: Boolean = false,
        val redirect: Boolean = false,
        val domainConstraints: Set<String> = emptySet(),
        val denyallow: Set<String> = emptySet(),
        val urlblock: Boolean = false,
        val fromScoped: Boolean = false,
        val toScoped: Boolean = false,
        val pathScoped: Boolean = false,
        val pathPattern: String? = null,
        val removeParams: Set<String> = emptySet(),
        val removeParamRegexes: Set<String> = emptySet(),
        val removeRequestHeaders: Set<String> = emptySet(),
        val setRequestHeaders: Set<String> = emptySet(),
        val replaceRules: Set<String> = emptySet(),
        val cspValue: String? = null,
        val redirectResource: String? = null,
        val cookieRemove: Set<String> = emptySet(),
        val cookieSet: Set<String> = emptySet(),
        val jsinject: String? = null,
        val requestTypeScoped: Boolean = false,
        val requestTypes: Set<String> = emptySet(),
        // 新增修饰符支持
        val ctag: Set<String> = emptySet(),
        val excludedCtag: Set<String> = emptySet(),
        val client: Set<String> = emptySet(),
        val notClient: Set<String> = emptySet(),
        val mac: Set<String> = emptySet(),
        val notMac: Set<String> = emptySet(),
        val asn: Set<String> = emptySet(),
        val notAsn: Set<String> = emptySet(),
        val network: Boolean = false,
        val blockIpv6: Boolean = false,
        val blockIpv4: Boolean = false,
        val generichide: Boolean = false,
        val generichideException: Boolean = false,
        val dnsrewrite: String? = null,
        val ctags: Set<String> = emptySet(),
        val unsupportedModifiers: List<String> = emptyList(),
        val invalid: Boolean = false
    )

    fun extractUnsupportedModifiers(
        modifierPart: String?,
        unsupportedAdGuardModifiers: Set<String>,
        ignorableAdGuardModifiers: Set<String>
    ): List<String> {
        if (modifierPart == null) return emptyList()
        val modifiers = modifierPart.split(',')
            .map { it.trim().removePrefix("~").substringBefore('=').lowercase() }
            .filter { it.isNotBlank() }
        if (modifiers.isEmpty()) return emptyList()
        return modifiers.filter { modifier ->
            unsupportedAdGuardModifiers.contains(modifier) && !ignorableAdGuardModifiers.contains(modifier)
        }
    }

    fun parseModifierInfo(
        modifierPart: String?,
        unsupportedAdGuardModifiers: Set<String>,
        ignorableAdGuardModifiers: Set<String>,
        sanitizeAppPackageToken: (String) -> String?,
        mapDnsTypeToken: (String) -> Int?,
        normalizeDnsTypes: (Set<Int>?) -> Set<Int>?,
        mergeDnsTypes: (Set<Int>?, Set<Int>?) -> Set<Int>?
    ): ModifierInfo {
        if (modifierPart == null) return ModifierInfo()
        val unsupported = extractUnsupportedModifiers(modifierPart, unsupportedAdGuardModifiers, ignorableAdGuardModifiers)
        if (unsupported.isNotEmpty()) return ModifierInfo(unsupportedModifiers = unsupported)

        var dnsTypes: Set<Int>? = null
        var excludedDnsTypes: Set<Int>? = null
        var badfilter = false
        var appScoped = false
        val appPackages = mutableSetOf<String>()
        val destinationPorts = mutableSetOf<Int>()
        val sourcePorts = mutableSetOf<Int>()
        var domainScoped = false
        var thirdParty = false
        var firstParty = false
        var redirect = false
        val domainConstraints = mutableSetOf<String>()
        val denyallow = mutableSetOf<String>()
        var urlblock = false
        var fromScoped = false
        var toScoped = false
        var pathScoped = false
        var pathPattern: String? = null
        val removeParams = mutableSetOf<String>()
        val removeParamRegexes = mutableSetOf<String>()
        val removeRequestHeaders = mutableSetOf<String>()
        val setRequestHeaders = mutableSetOf<String>()
        val replaceRules = mutableSetOf<String>()
        var cspValue: String? = null
        var redirectResource: String? = null
        val cookieRemove = mutableSetOf<String>()
        val cookieSet = mutableSetOf<String>()
        var jsinject: String? = null
        var requestTypeScoped = false
        val requestTypes = mutableSetOf<String>()
        // 新增修饰符变量
        val ctag = mutableSetOf<String>()
        val excludedCtag = mutableSetOf<String>()
        val client = mutableSetOf<String>()
        val notClient = mutableSetOf<String>()
        val mac = mutableSetOf<String>()
        val notMac = mutableSetOf<String>()
        val asn = mutableSetOf<String>()
        val notAsn = mutableSetOf<String>()
        val ctags = mutableSetOf<String>()
        var network = false
        var blockIpv6 = false
        var blockIpv4 = false
        var generichide = false
        var generichideException = false
        var dnsrewrite: String? = null

        modifierPart.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { rawModifier ->
                val inverted = rawModifier.startsWith("~")
                val modifier = rawModifier.removePrefix("~")
                val name = modifier.substringBefore('=').trim().lowercase()
                val value = modifier.substringAfter('=', missingDelimiterValue = "").trim()
                when (name) {
                    in ignorableAdGuardModifiers -> Unit
                    "important", "match-case" -> Unit
                    "badfilter" -> badfilter = true
                    "app" -> {
                        if (inverted || value.isBlank()) return ModifierInfo(invalid = true)
                        appScoped = true
                        val packages = value.split('|').mapNotNull(sanitizeAppPackageToken)
                        if (packages.isEmpty()) return ModifierInfo(invalid = true)
                        appPackages.addAll(packages)
                    }
                    "dst-port" -> {
                        val ports = parsePortModifierValues(value, inverted) ?: return ModifierInfo(invalid = true)
                        destinationPorts.addAll(ports)
                    }
                    "src-port" -> {
                        val ports = parsePortModifierValues(value, inverted) ?: return ModifierInfo(invalid = true)
                        sourcePorts.addAll(ports)
                    }
                    "domain" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        domainScoped = true
                        domainConstraints.addAll(
                            value.split('|').map { it.trim().lowercase().removePrefix("~") }.filter { it.isNotBlank() }
                        )
                    }
                    "dnstype" -> {
                        if (inverted || value.isBlank()) return ModifierInfo(invalid = true)
                        val tokens = value.split('|').map { it.trim() }.filter { it.isNotBlank() }
                        val includeTokens = tokens.filterNot { it.startsWith("~") }
                        val excludeTokens = tokens.filter { it.startsWith("~") }.map { it.removePrefix("~").trim() }
                        if (includeTokens.isEmpty() && excludeTokens.isEmpty()) return ModifierInfo(invalid = true)
                        if (includeTokens.isNotEmpty()) {
                            val parsedTypes = includeTokens.mapNotNull(mapDnsTypeToken).toSet()
                            if (parsedTypes.isEmpty() || parsedTypes.size != includeTokens.size) return ModifierInfo(invalid = true)
                            dnsTypes = mergeDnsTypes(dnsTypes, parsedTypes)
                        }
                        if (excludeTokens.isNotEmpty()) {
                            val parsedExcludedTypes = excludeTokens.mapNotNull(mapDnsTypeToken).toSet()
                            if (parsedExcludedTypes.isEmpty() || parsedExcludedTypes.size != excludeTokens.size) return ModifierInfo(invalid = true)
                            excludedDnsTypes = mergeDnsTypes(excludedDnsTypes, parsedExcludedTypes)
                        }
                    }
                    "third-party", "3p" -> if (inverted) firstParty = true else thirdParty = true
                    "first-party", "1p" -> if (inverted) thirdParty = true else firstParty = true
                    "redirect", "redirect-rule" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        redirect = true
                        redirectResource = value.lowercase()
                    }
                    "denyallow" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val domains = value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (domains.isEmpty()) return ModifierInfo(invalid = true)
                        denyallow.addAll(domains)
                    }
                    "removeparam" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val tokens = value.split('|').map { it.trim() }.filter { it.isNotBlank() }
                        if (tokens.isEmpty()) return ModifierInfo(invalid = true)
                        tokens.forEach { token ->
                            when (val parsedToken = parseRemoveParamToken(token)) {
                                is RemoveParamToken.Exact -> removeParams += parsedToken.name
                                is RemoveParamToken.Regex -> removeParamRegexes += parsedToken.pattern
                                null -> return ModifierInfo(invalid = true)
                            }
                        }
                    }
                    "removeheader" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val headerNames = value.split('|')
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() }
                        if (headerNames.isEmpty() || headerNames.any { !isValidHeaderName(it) }) return ModifierInfo(invalid = true)
                        removeRequestHeaders.addAll(headerNames)
                    }
                    "header" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val normalizedHeader = parseHeaderOverrideRule(value) ?: return ModifierInfo(invalid = true)
                        setRequestHeaders += normalizedHeader
                    }
                    "replace" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val normalizedRule = parseReplaceRule(value) ?: return ModifierInfo(invalid = true)
                        replaceRules += normalizedRule
                    }
                    "csp" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        cspValue = value
                    }
                    "urlblock" -> urlblock = true
                    "from" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        fromScoped = true
                    }
                    "to" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        toScoped = true
                    }
                    "path" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        pathScoped = true
                        pathPattern = value.lowercase()
                    }
                    "cookie" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val cookieRules = value.split('|').map { it.trim() }.filter { it.isNotBlank() }
                        cookieRules.forEach { rule ->
                            if (rule.startsWith("~")) {
                                cookieRemove += rule.removePrefix("~").trim()
                            } else {
                                cookieSet += rule
                            }
                        }
                    }
                    "jsinject" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        jsinject = value
                    }
                    "script", "stylesheet", "image", "media", "font", "xmlhttprequest", "subdocument", "object", "object-subrequest", "ping", "websocket", "webrtc", "other", "popup" -> {
                        requestTypeScoped = true
                        requestTypes += name
                    }
                    // 新增修饰符支持
                    "ctag" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val tags = value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (tags.isEmpty()) return ModifierInfo(invalid = true)
                        ctag.addAll(tags)
                    }
                    "excluded_ctag", "excluded-client-tag" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val tags = value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (tags.isEmpty()) return ModifierInfo(invalid = true)
                        excludedCtag.addAll(tags)
                    }
                    "client" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val clients = value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (clients.isEmpty()) return ModifierInfo(invalid = true)
                        client.addAll(clients)
                    }
                    "notclient", "~client" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val clients = value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (clients.isEmpty()) return ModifierInfo(invalid = true)
                        notClient.addAll(clients)
                    }
                    "mac" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val macs = value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (macs.isEmpty()) return ModifierInfo(invalid = true)
                        mac.addAll(macs)
                    }
                    "notmac", "~mac" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val macs = value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (macs.isEmpty()) return ModifierInfo(invalid = true)
                        notMac.addAll(macs)
                    }
                    "asn" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val asns = value.split('|').map { it.trim().uppercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (asns.isEmpty()) return ModifierInfo(invalid = true)
                        asn.addAll(asns)
                    }
                    "notasn", "~asn" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val asns = value.split('|').map { it.trim().uppercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (asns.isEmpty()) return ModifierInfo(invalid = true)
                        notAsn.addAll(asns)
                    }
                    "network" -> network = true
                    "blockipv6" -> blockIpv6 = true
                    "blockipv4" -> blockIpv4 = true
                    "generichide" -> generichide = true
                    "generichide-exception" -> generichideException = true
                    "dnsrewrite" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        dnsrewrite = value
                    }
                    "ctags" -> {
                        if (value.isBlank()) return ModifierInfo(invalid = true)
                        val tags = value.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() && !it.startsWith("~") }
                        if (tags.isEmpty()) return ModifierInfo(invalid = true)
                        ctags.addAll(tags)
                    }
                }
            }

        val normalizedIncluded = normalizeDnsTypes(dnsTypes)
        val normalizedExcluded = normalizeDnsTypes(excludedDnsTypes)
        if (normalizedIncluded != null && normalizedExcluded != null && normalizedIncluded.any(normalizedExcluded::contains)) {
            return ModifierInfo(invalid = true)
        }
        return ModifierInfo(
            dnsTypes = normalizedIncluded,
            excludedDnsTypes = normalizedExcluded,
            badfilter = badfilter,
            appScoped = appScoped,
            appPackages = appPackages.toSet(),
            destinationPorts = destinationPorts.toSet(),
            sourcePorts = sourcePorts.toSet(),
            domainScoped = domainScoped,
            thirdParty = thirdParty,
            firstParty = firstParty,
            redirect = redirect,
            domainConstraints = domainConstraints.toSet(),
            denyallow = denyallow.toSet(),
            urlblock = urlblock,
            fromScoped = fromScoped,
            toScoped = toScoped,
            pathScoped = pathScoped,
            pathPattern = pathPattern,
            removeParams = removeParams.toSet(),
            removeParamRegexes = removeParamRegexes.toSet(),
            removeRequestHeaders = removeRequestHeaders.toSet(),
            setRequestHeaders = setRequestHeaders.toSet(),
            replaceRules = replaceRules.toSet(),
            cspValue = cspValue,
            redirectResource = redirectResource,
            cookieRemove = cookieRemove.toSet(),
            cookieSet = cookieSet.toSet(),
            jsinject = jsinject,
            requestTypeScoped = requestTypeScoped,
            requestTypes = requestTypes.toSet(),
            // 新增修饰符
            ctag = ctag.toSet(),
            excludedCtag = excludedCtag.toSet(),
            client = client.toSet(),
            notClient = notClient.toSet(),
            mac = mac.toSet(),
            notMac = notMac.toSet(),
            asn = asn.toSet(),
            notAsn = notAsn.toSet(),
            network = network,
            blockIpv6 = blockIpv6,
            blockIpv4 = blockIpv4,
            generichide = generichide,
            generichideException = generichideException,
            dnsrewrite = dnsrewrite,
            ctags = ctags.toSet()
        )
    }

    private fun parseHeaderOverrideRule(value: String): String? {
        val separatorIndex = value.indexOf(':')
        if (separatorIndex <= 0) return null
        val headerName = value.substring(0, separatorIndex).trim().lowercase()
        if (!isValidHeaderName(headerName)) return null
        val headerValue = value.substring(separatorIndex + 1).trim()
        return "$headerName\u0000$headerValue"
    }

    private fun parseReplaceRule(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("/") || trimmed.length < 3) return null
        val parts = splitSlashDelimitedReplaceParts(trimmed)
        if (parts == null || parts.size !in 2..3) return null
        val pattern = parts[0].trim()
        val replacement = parts[1]
        val flags = parts.getOrNull(2)?.trim()?.lowercase().orEmpty()
        if (pattern.isBlank()) return null
        if (!flags.all { it == 'i' || it == 'g' || it == 'm' || it == 's' }) return null
        return runCatching {
            buildRegexOptions(flags)
            Regex(pattern, buildRegexOptions(flags))
            "$pattern\u0000$replacement\u0000$flags"
        }.getOrNull()
    }

    private fun splitSlashDelimitedReplaceParts(value: String): List<String>? {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (index in 1 until value.length) {
            val ch = value[index]
            if (escaped) {
                current.append(ch)
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                current.append(ch)
                continue
            }
            if (ch == '/') {
                parts += current.toString()
                current.clear()
                continue
            }
            current.append(ch)
        }
        return when {
            parts.size >= 2 -> parts
            else -> null
        }
    }

    private fun buildRegexOptions(flags: String): Set<RegexOption> {
        val options = linkedSetOf<RegexOption>()
        flags.forEach { flag ->
            when (flag) {
                'i' -> options += RegexOption.IGNORE_CASE
                'm' -> options += RegexOption.MULTILINE
                's' -> options += RegexOption.DOT_MATCHES_ALL
            }
        }
        return options
    }

    private fun isValidHeaderName(value: String): Boolean {
        return value.isNotBlank() && value.all { ch ->
            ch.isLetterOrDigit() || ch == '-'
        }
    }

    fun parseRemoveParamToken(token: String): RemoveParamToken? {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("/") && trimmed.endsWith("/") && trimmed.length > 2) {
            val body = trimmed.substring(1, trimmed.length - 1).trim()
            if (body.isBlank()) return null
            return runCatching { Regex(body) }.getOrNull()?.pattern?.let(RemoveParamToken::Regex)
        }
        return trimmed.lowercase().takeIf { it.isNotBlank() }?.let(RemoveParamToken::Exact)
    }

    fun parsePortModifierValues(value: String, inverted: Boolean): Set<Int>? {
        if (inverted || value.isBlank()) return null
        val ports = value.split('|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { token ->
                val port = token.toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                port
            }
            .toSet()
        return ports.takeIf { it.isNotEmpty() }
    }

    fun mapDnsTypeToken(token: String): Int? {
        return when (token.trim().uppercase()) {
            "A" -> 1
            "NS" -> 2
            "CNAME" -> 5
            "SOA" -> 6
            "PTR" -> 12
            "MX" -> 15
            "TXT" -> 16
            "AAAA" -> 28
            "SRV" -> 33
            "NAPTR" -> 35
            "SVCB" -> 64
            "HTTPS" -> 65
            "CAA" -> 257
            "ANY" -> 255
            else -> null
        }
    }

    fun normalizeDnsTypes(dnsTypes: Set<Int>?): Set<Int>? {
        if (dnsTypes.isNullOrEmpty()) return null
        if (dnsTypes.contains(255)) return null
        return dnsTypes.toSortedSet()
    }

    fun mergeDnsTypes(existing: Set<Int>?, incoming: Set<Int>?): Set<Int>? {
        val normalizedExisting = normalizeDnsTypes(existing)
        val normalizedIncoming = normalizeDnsTypes(incoming)
        if (normalizedExisting == null || normalizedIncoming == null) return null
        return (normalizedExisting + normalizedIncoming).toSortedSet()
    }
}
