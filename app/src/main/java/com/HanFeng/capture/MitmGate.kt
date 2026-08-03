package com.HanFeng.capture

/**
 * 抽象证书与 MITM 全局开关的状态查询/控制, 让 [CaptureController] 依赖接口而非 Kotlin `object`,
 * 方便单元测试用普通 mockito mock 接口而非 mockStatic(后者对 Kotlin object 实例方法拦截不确定)。
 *
 * 生产实现 [DefaultMitmGate] 把调用桥接到 [com.HanFeng.data.HttpsMitmRepository] /
 * [com.HanFeng.data.FeatureSettingsRepository], 维持 design correctness 2 / 10 不变。
 */
interface MitmGate {
    /** 本地 CA 是否已生成且 PKCS12 文件可读。 */
    fun isCertificateReady(context: android.content.Context): Boolean

    /** 系统是否已安装本 App 的根证书。 */
    fun isCertificateInstalled(context: android.content.Context): Boolean

    /** HTTPS MITM 全局开关是否已开。 */
    fun isHttpDecryptEnabled(context: android.content.Context): Boolean

    /** 开/关 HTTPS MITM 全局开关。 */
    fun setHttpDecryptEnabled(context: android.content.Context, enabled: Boolean)
}

class DefaultMitmGate : MitmGate {
    override fun isCertificateReady(context: android.content.Context): Boolean =
        com.HanFeng.data.HttpsMitmRepository.isCertificateReady(context)

    override fun isCertificateInstalled(context: android.content.Context): Boolean =
        com.HanFeng.data.HttpsMitmRepository.isCertificateInstalled(context)

    override fun isHttpDecryptEnabled(context: android.content.Context): Boolean =
        com.HanFeng.data.FeatureSettingsRepository.isHttpDecryptEnabled(context)

    override fun setHttpDecryptEnabled(context: android.content.Context, enabled: Boolean) {
        com.HanFeng.data.FeatureSettingsRepository.setHttpDecryptEnabled(context, enabled)
    }
}
