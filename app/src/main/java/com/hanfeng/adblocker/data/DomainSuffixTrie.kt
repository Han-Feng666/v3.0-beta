package com.HanFeng.data

class DomainSuffixTrie {
    private class TrieNode {
        val children = HashMap<String, TrieNode>()
        var isEnd = false
    }

    private val root = TrieNode()

    fun insert(domain: String) {
        val labels = domain.split('.').reversed()
        var node = root
        for (label in labels) {
            node = node.children.getOrPut(label) { TrieNode() }
        }
        node.isEnd = true
    }

    fun insertAll(domains: Collection<String>) {
        domains.forEach { insert(it) }
    }

    fun contains(domain: String): Boolean {
        val labels = domain.split('.').reversed()
        var node = root
        for (label in labels) {
            node = node.children[label] ?: return false
            if (node.isEnd) return true
        }
        return node.isEnd
    }

    companion object {
        fun fromDomains(domains: Collection<String>): DomainSuffixTrie {
            return DomainSuffixTrie().also { it.insertAll(domains) }
        }
    }
}
