package com.HanFeng.data

class DomainTrieIndex(
    blocked: Set<String>,
    userOwnedBlocked: Set<String>,
    importantBlocked: Set<String>,
    exceptions: Set<String>
) {
    companion object {
        private const val FLAG_BLOCKED = 1
        private const val FLAG_EXCEPTION = 2
        private const val FLAG_IMPORTANT = 4
        private const val FLAG_USER_OWNED = 8
        private const val WILDCARD_LABEL = "*"
    }

    private class TrieNode {
        val children = HashMap<String, TrieNode>()
        var flags = 0
    }

    private val root = TrieNode()

    init {
        blocked.forEach { insert(it, FLAG_BLOCKED) }
        exceptions.forEach { insert(it, FLAG_EXCEPTION) }
        importantBlocked.forEach { insert(it, FLAG_IMPORTANT) }
        userOwnedBlocked.forEach { insert(it, FLAG_USER_OWNED) }
    }

    private fun insert(domain: String, flag: Int) {
        val labels = domain.split('.')
        var node = root
        for (i in labels.indices.reversed()) {
            node = node.children.getOrPut(labels[i]) { TrieNode() }
        }
        node.flags = node.flags or flag
    }

    fun hasBlocked(domain: String): Boolean = walk(domain) { (it.flags and (FLAG_BLOCKED or FLAG_IMPORTANT)) != 0 }

    fun hasException(domain: String): Boolean = walk(domain) { (it.flags and FLAG_EXCEPTION) != 0 }

    fun hasUserOwnedBlock(domain: String): Boolean = walk(domain) { (it.flags and FLAG_USER_OWNED) != 0 }

    fun hasImportantBlock(domain: String): Boolean = walk(domain) { (it.flags and FLAG_IMPORTANT) != 0 }

    private inline fun walk(domain: String, predicate: (TrieNode) -> Boolean): Boolean {
        val labels = domain.split('.')
        var node = root
        for (i in labels.indices.reversed()) {
            val specific = node.children[labels[i]]
            if (specific != null) {
                node = specific
                if (predicate(node)) return true
            } else {
                node = node.children[WILDCARD_LABEL] ?: return false
                if (predicate(node)) return true
            }
        }
        return predicate(node)
    }
}
