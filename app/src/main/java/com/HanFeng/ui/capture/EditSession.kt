package com.HanFeng.ui.capture

import com.HanFeng.capture.CaptureDraftRequest
import com.HanFeng.capture.CaptureDraftResponse

/**
 * CaptureDetailActivity 持有当前编辑会话:用户在 "请求" 与 "响应" Tab 中可编辑的草稿缓存,
 * 仅生命周期内有效(design correctness 8)。
 *
 * 注意:
 * - 仅"应用替换"或"加入断点 + 替换"按钮触发实际入网或本地返; 不触动 ring buffer 中的 entry
 * - 改响应草稿替入为 BreakpointActionExecutor 的 response 字节; 改请求为 WriteRequest 流程
 *
 * 引用 design Components #12 / requirements R8.3 / R10。
 */
class EditSession {

    var requestDraft: CaptureDraftRequest? = null
        private set
    var responseDraft: CaptureDraftResponse? = null
        private set

    /** 收集请求草稿;由 RequestTabFragment 写入。 */
    fun setRequestDraft(draft: CaptureDraftRequest) {
        requestDraft = draft
    }

    /** 收集响应草稿;由 ResponseTabFragment 写入。 */
    fun setResponseDraft(draft: CaptureDraftResponse) {
        responseDraft = draft
    }

    fun hasRequestDraft(): Boolean = requestDraft != null
    fun hasResponseDraft(): Boolean = responseDraft != null

    fun resetAll() {
        requestDraft = null
        responseDraft = null
    }
}
