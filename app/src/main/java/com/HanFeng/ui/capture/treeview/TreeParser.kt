package com.HanFeng.ui.capture.treeview

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用扁平树节点; 既可表达 JSON 对象/数组, 也可表达 XML 元素。
 * 引用批次 B: requirements R12 (视图模式三态)。
 */
data class TreeNode(
    val name: String,
    val value: String?,
    val depth: Int,
    val children: List<TreeNode>,
    val isContainer: Boolean
)

/**
 * 把字符流解析为扁平 TreeNode 列表。
 *
 * 当前实现范围:
 *  - JSON (object/array/scalar): 用 org.json 解析
 *  - XML: 简易 SAX 解析
 *  - 其它返回 null, 让 UI 走 Raw 兜底
 */
object TreeParser {

    fun parse(body: ByteArray?, contentType: String?): List<TreeNode>? {
        if (body == null || body.isEmpty()) return null
        val ct = contentType?.lowercase() ?: ""
        return when {
            ct.contains("grpc") -> parseGrpc(body, ct)
            ct.contains("websocket") || ct.contains("upgrade") -> parseWebSocket(body)
            ct.contains("json", ignoreCase = true) -> parseJson(String(body, Charsets.UTF_8))
            ct.contains("xml", ignoreCase = true) -> parseXml(String(body, Charsets.UTF_8))
            else -> {
                // 批次 C4: WS/gRPC 帧可以无 content-type 显式标识; 通过 magic byte 嗅探兜底。
                val sniffer = sniffProtocol(body)
                when (sniffer) {
                    "grpc" -> parseGrpc(body, ct)
                    "ws" -> parseWebSocket(body)
                    else -> tryJsonFallback(String(body, Charsets.UTF_8))
                }
            }
        }
    }

    /** 通过 WS/gRPC magic byte 弱嗅探(避免误伤)。 */
    private fun sniffProtocol(body: ByteArray): String? {
        // gRPC Length-Prefixed Message: 第 1 字节 compressed (0/1), 后 4 字节 BE length, 全文 ASCII-safe 概率低
        if (body.size >= 5) {
            val compressed = body[0].toInt() and 0xFF
            val len = ((body[1].toInt() and 0xFF) shl 24) or
                ((body[2].toInt() and 0xFF) shl 16) or
                ((body[3].toInt() and 0xFF) shl 8) or
                (body[4].toInt() and 0xFF)
            if (compressed in 0..1 && len in 1..(16 * 1024 * 1024) && body.size >= 5 + len) {
                return "grpc"
            }
        }
        // WebSocket: 第一字节高 bit (FIN) 通常 1 (=0x80+opcode), 第二字节 RSSV 位 = 0, 实际 WS 帧极特异
        if (body.size >= 2) {
            val b0 = body[0].toInt() and 0xFF
            val rsv = (b0 ushr 4) and 0x07
            val opcode = b0 and 0x0F
            val b1 = body[1].toInt() and 0xFF
            if (rsv == 0 && opcode in 0..0xA && b1 and 0x70 == 0 && (b1 and 0x7F) in 0..127) {
                return "ws"
            }
        }
        return null
    }

    private fun parseWebSocket(body: ByteArray): List<TreeNode>? {
        val res = com.HanFeng.capture.WsGrpcFrameDecoder.decodeWebSocketFrames(body)
        if (res.frames.isEmpty() && res.error == null) return null
        val out = ArrayList<TreeNode>()
        out.add(TreeNode(name = "WebSocket frames", value = res.error ?: "${res.frames.size} frames", depth = 0, children = emptyList(), isContainer = true))
        res.frames.forEachIndexed { i, f ->
            val child = TreeNode(
                name = "frame[$i]",
                value = "fin=${f.fin} op=${f.opcode.name} payload=${f.payload.size}B${if (f.maskedFromClient) " [masked]" else ""}",
                depth = 1,
                children = emptyList(),
                isContainer = false
            )
            out.add(child)
        }
        if (res.error != null) {
            out.add(TreeNode(name = "error", value = res.error, depth = 1, children = emptyList(), isContainer = false))
        }
        return out
    }

    private fun parseGrpc(body: ByteArray, ct: String): List<TreeNode>? {
        val res = com.HanFeng.capture.WsGrpcFrameDecoder.decodeGrpcFrames(body)
        if (res.frames.isEmpty() && res.error == null) return null
        val out = ArrayList<TreeNode>()
        out.add(TreeNode(name = "gRPC messages", value = res.error ?: "${res.frames.size} messages", depth = 0, children = emptyList(), isContainer = true))
        res.frames.forEachIndexed { i, f ->
            out.add(TreeNode(
                name = "msg[$i]",
                value = "compressed=${f.compressed} len=${f.message.size}B",
                depth = 1,
                children = emptyList(),
                isContainer = false
            ))
        }
        if (res.error != null) {
            out.add(TreeNode(name = "error", value = res.error, depth = 1, children = emptyList(), isContainer = false))
        }
        return out
    }

    // ==================== JSON ====================

    private fun parseJson(text: String): List<TreeNode>? {
        return try {
            val trimmed = text.trim()
            val nodes = ArrayList<TreeNode>()
            val root: Any? = if (trimmed.startsWith("{")) JSONObject(trimmed).toAny()
            else if (trimmed.startsWith("[")) JSONArray(trimmed).toAnyArray()
            else return null
            buildJsonNodes(root, depth = 0, key = "", nodes)
            nodes
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun JSONObject.toAny(): Map<String, Any?> =
        keys().asSequence().associateWith { get(it) }

    private fun JSONArray.toAnyArray(): List<Any?> =
        (0 until length()).map { get(it) }

    private fun buildJsonNodes(v: Any?, depth: Int, key: String, out: ArrayList<TreeNode>) {
        when (v) {
            is Map<*, *> -> {
                out.add(TreeNode(name = key.ifEmpty { "{ }" }, value = null, depth = depth, children = emptyList(), isContainer = true))
                v.entries.forEach { (k, child) ->
                    buildJsonNodes(child, depth + 1, k?.toString() ?: "", out)
                }
                out.add(TreeNode(name = "}", value = null, depth = depth, children = emptyList(), isContainer = false))
            }
            is List<*> -> {
                out.add(TreeNode(name = key.ifEmpty { "[ ]" }, value = null, depth = depth, children = emptyList(), isContainer = true))
                v.forEachIndexed { i, child ->
                    buildJsonNodes(child, depth + 1, "[$i]", out)
                }
                out.add(TreeNode(name = "]", value = null, depth = depth, children = emptyList(), isContainer = false))
            }
            null -> out.add(TreeNode(name = key, value = "null", depth = depth, children = emptyList(), isContainer = false))
            else -> out.add(TreeNode(name = key, value = v.toString(), depth = depth, children = emptyList(), isContainer = false))
        }
    }

    private fun tryJsonFallback(text: String): List<TreeNode>? {
        return try {
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseJson(text) else null
        } catch (_: Throwable) { null }
    }

    // ==================== XML ====================

    /** 极简 XML 解析: 仅识别标签、属性、文本节点, 注释/CDATA 简化处理。 */
    private fun parseXml(text: String): List<TreeNode>? {
        return try {
            val out = ArrayList<TreeNode>()
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                try { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Throwable) {}
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(org.xml.sax.InputSource(java.io.StringReader(text)))
            walkXml(doc.documentElement, depth = 0, out)
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun walkXml(node: org.w3c.dom.Node, depth: Int, out: ArrayList<TreeNode>) {
        val sb = StringBuilder()
        sb.append('<').append(node.nodeName)
        val attrs = node.attributes
        if (attrs != null) {
            for (i in 0 until attrs.length) {
                val a = attrs.item(i)
                sb.append(' ').append(a.nodeName).append("=\"").append(a.nodeValue).append('"')
            }
        }
        sb.append('>')
        out.add(TreeNode(name = sb.toString(), value = null, depth = depth, children = emptyList(), isContainer = true))
        if (node.childNodes?.length ?: 0 > 0) {
            for (i in 0 until (node.childNodes.length)) {
                val child = node.childNodes.item(i)
                when (child.nodeType) {
                    org.w3c.dom.Node.ELEMENT_NODE -> walkXml(child, depth + 1, out)
                    org.w3c.dom.Node.TEXT_NODE -> {
                        val t = child.nodeValue?.trim()
                        if (!t.isNullOrEmpty()) {
                            out.add(TreeNode(name = "#text", value = t, depth = depth + 1, children = emptyList(), isContainer = false))
                        }
                    }
                    else -> { /* ignore comments/PIs */ }
                }
            }
        }
        out.add(TreeNode(name = "</${node.nodeName}>", value = null, depth = depth, children = emptyList(), isContainer = false))
    }
}
