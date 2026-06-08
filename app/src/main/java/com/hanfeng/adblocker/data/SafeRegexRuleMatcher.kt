package com.HanFeng.data

import java.util.regex.Pattern

object SafeRegexRuleMatcher {
    data class CacheState(
        val compiledPatterns: Map<String, Pattern>,
        val invalidPatterns: Set<String>
    )

    data class MatchResult(
        val matched: Boolean,
        val cacheState: CacheState
    )

    fun matches(
        pattern: String?,
        value: String,
        cacheState: CacheState
    ): MatchResult {
        val safePattern = pattern ?: return MatchResult(false, cacheState)
        if (cacheState.invalidPatterns.contains(safePattern)) {
            return MatchResult(false, cacheState)
        }
        val cached = cacheState.compiledPatterns[safePattern]
        if (cached != null) {
            return MatchResult(cached.matcher(value).find(), cacheState)
        }
        val compiled = runCatching {
            Pattern.compile(safePattern, Pattern.CASE_INSENSITIVE)
        }.getOrNull()
        if (compiled == null) {
            return MatchResult(
                matched = false,
                cacheState = cacheState.copy(invalidPatterns = cacheState.invalidPatterns + safePattern)
            )
        }
        val nextState = cacheState.copy(compiledPatterns = cacheState.compiledPatterns + (safePattern to compiled))
        return MatchResult(
            matched = compiled.matcher(value).find(),
            cacheState = nextState
        )
    }
}
