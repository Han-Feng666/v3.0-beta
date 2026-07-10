package com.HanFeng.core.network

/**
 * Domain Generation Algorithm (DGA) heuristic detector.
 *
 * Targets ad/tracker SDKs that rotate subdomains to evade static blocklists.
 * Uses simple structural and entropy signals rather than ML.
 */
internal object DgaPatternDetector {

    private const val MIN_LABEL_LEN_FOR_DGA = 8
    private const val HIGH_ENTROPY_THRESHOLD = 0.70
    private val consonantClusters = Regex(pattern = "[bcdfghjklmnpqrstvwxz]{5,}")
    private val randomLookingHex = Regex(pattern = "^[a-f0-9]{12,}$")
    private val randomLookingBase32 = Regex(pattern = "^[a-z2-7]{12,}$")
    private val pureDigits = Regex(pattern = "^\\d{8,}$")
    private val repeatedDashes = Regex(pattern = "(-([a-z0-9]{1,3})-){3,}")
    private val publishHostSuffixes = listOf(".akamaaihd.net", ".edgesuite.net", ".cloudfront.net", ".fastly.net", ".bashify.io")

    fun looksLikeDga(domain: String): Boolean {
        val normalized = domain.trim().lowercase().removeSuffix(".")
        if (normalized.isEmpty()) return false
        if (normalized.contains(":")) return false

        val leftmostLabel = normalized.substringBefore('.')
        if (leftmostLabel.length < MIN_LABEL_LEN_FOR_DGA) return false

        for (suffix in publishHostSuffixes) {
            if (normalized.endsWith(suffix, ignoreCase = true)) return false
        }

        if (randomLookingHex.matches(leftmostLabel)) return true
        if (randomLookingBase32.matches(leftmostLabel)) return true
        if (pureDigits.matches(leftmostLabel)) return true
        if (consonantClusters.containsMatchIn(leftmostLabel)) return true
        if (repeatedDashes.containsMatchIn(leftmostLabel)) return true
        if (shannonEntropy(leftmostLabel) > HIGH_ENTROPY_THRESHOLD) return true

        return false
    }

    private fun shannonEntropy(value: String): Double {
        if (value.isEmpty()) return 0.0
        val map = HashMap<Char, Int>()
        for (c in value) map[c] = (map[c] ?: 0) + 1
        var entropy = 0.0
        val log2 = Math.log(2.0)
        val len = value.length.toDouble()
        for (count in map.values) {
            if (count <= 0) continue
            val p = count / len
            entropy -= p * (Math.log(p) / log2)
        }
        return entropy * (value.length / 24.0)
    }
}
