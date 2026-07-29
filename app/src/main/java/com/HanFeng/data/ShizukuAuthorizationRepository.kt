package com.HanFeng.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

private const val FLAG_ALLOWED = 1 shl 1
private const val FLAG_DENIED = 1 shl 2
private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

/**
 * 使用官方 Shizuku 底层 binder API 管理应用授权。
 * 不使用 AuthorizationManager（需要独立 Shizuku Manager APK），
 * 直接调用 Shizuku.updateFlagsForUid / getFlagsForUid，
 * 与官方 Shizuku Manager 的授权逻辑一致。
 */
object ShizukuAuthorizationRepository {

    private const val TAG = "ShizukuAuthz"

    fun isServerAlive(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false) ||
            runCatching { Shizuku.getBinder()?.isBinderAlive == true }.getOrDefault(false)
    }

    /**
     * 使用本地 PackageManager 列举已安装应用，通过 Shizuku binder 查询授权状态。
     */
    fun listInstalledAppsForAuth(context: android.content.Context): List<AuthorizedApp> {
        if (!isServerAlive()) {
            Log.w(TAG, "listInstalledAppsForAuth: Shizuku server not alive")
            return emptyList()
        }
        return try {
            val pm = context.packageManager
            pm.getInstalledPackages(0).mapNotNull { pkgInfo ->
                try {
                    val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                    val uid = appInfo.uid
                    val pkgName = pkgInfo.packageName
                    val flags = runCatching { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) }.getOrDefault(0)
                    val isAllowed = (flags and FLAG_ALLOWED) == FLAG_ALLOWED
                    AuthorizedApp(
                        uid = uid,
                        packageName = pkgName,
                        label = pkgName,
                        icon = null,
                        isAllowed = isAllowed,
                        isDenied = !isAllowed,
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "load app info failed: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listInstalledAppsForAuth failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 给指定 UID 授予 Shizuku 权限。
     * 使用 Shizuku.updateFlagsForUid 直接操作 server 端授权表，
     * 不依赖外部 AuthorizationManager。
     */
    fun grantAuthorization(packageName: String, uid: Int): Boolean {
        if (!isServerAlive()) {
            Log.w(TAG, "grantAuthorization: Shizuku server not alive")
            return false
        }
        val ok = try {
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
            Thread.sleep(200)
            val flags = runCatching { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) }.getOrDefault(0)
            val verified = (flags and FLAG_ALLOWED) == FLAG_ALLOWED
            Log.i(TAG, "grantAuthorization $packageName uid=$uid flags=$flags verified=$verified")
            verified
        } catch (e: Exception) {
            Log.e(TAG, "grantAuthorization failed for $packageName uid=$uid: ${e.message}", e)
            false
        }

        if (!ok) {
            Log.w(TAG, "grantAuthorization: binder failed, trying root fallback for $packageName uid=$uid")
            return grantAuthorizationViaRoot(packageName, uid)
        }
        return true
    }

    fun revokeAuthorization(packageName: String, uid: Int): Boolean {
        if (!isServerAlive()) {
            Log.w(TAG, "revokeAuthorization: Shizuku server not alive")
            return false
        }
        val ok = try {
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
            Thread.sleep(200)
            val flags = runCatching { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) }.getOrDefault(0)
            val verified = (flags and FLAG_ALLOWED) == 0
            Log.i(TAG, "revokeAuthorization $packageName uid=$uid flags=$flags verified=$verified")
            verified
        } catch (e: Exception) {
            Log.e(TAG, "revokeAuthorization failed for $packageName uid=$uid: ${e.message}", e)
            false
        }

        if (!ok) {
            Log.w(TAG, "revokeAuthorization: binder failed, trying root fallback for $packageName uid=$uid")
            return revokeAuthorizationViaRoot(packageName, uid)
        }
        return true
    }

    private fun grantAuthorizationViaRoot(packageName: String, uid: Int): Boolean {
        return try {
            val session = com.HanFeng.adblocker.shizuku.SuSession.getInstance()
            if (!session.isSessionOpen() && !session.open(10)) {
                Log.w(TAG, "grantViaRoot: SuSession not open")
                return false
            }
            // 1. 授予运行时权限
            session.execute("pm grant $packageName com.HanFeng.permission.shizuku.API_V23 2>&1")
            // 2. 写 shizuku.json 配置
            session.execute(
                "CONF=/data/user_de/0/com.android.shell/shizuku.json;" +
                "if [ -f \"\$CONF\" ];then" +
                " sed -i '/\"uid\":$uid,/d' \"\$CONF\" 2>/dev/null;" +
                " sed -i 's/^{/{\"packages\":[{\"uid\":$uid,\"flags\":2,\"packages\":[\"$packageName\"]},/' \"\$CONF\" 2>/dev/null;" +
                "else echo '{\"packages\":[{\"uid\":$uid,\"flags\":2,\"packages\":[\"$packageName\"]}]}' > \"\$CONF\";fi"
            )
            // 3. 重启 Shizuku server
            session.execute("killall app_process 2>/dev/null", timeoutSeconds = 3)
            Thread.sleep(2500)
            val flags = try { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) } catch (_: Exception) { 0 }
            val ok = (flags and FLAG_ALLOWED) == FLAG_ALLOWED
            Log.i(TAG, "grantViaRoot final: flags=$flags ok=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "grantViaRoot failed: ${e.message}", e)
            false
        }
    }

    private fun revokeAuthorizationViaRoot(packageName: String, uid: Int): Boolean {
        return try {
            val session = com.HanFeng.adblocker.shizuku.SuSession.getInstance()
            if (!session.isSessionOpen() && !session.open(10)) return false
            session.execute("pm revoke $packageName com.HanFeng.permission.shizuku.API_V23 2>&1")
            session.execute(
                "CONF=/data/user_de/0/com.android.shell/shizuku.json;" +
                "if [ -f \"\$CONF\" ];then" +
                " sed -i '/\"uid\":$uid,/d' \"\$CONF\" 2>/dev/null;" +
                "fi"
            )
            session.execute("killall app_process 2>/dev/null", timeoutSeconds = 3)
            Thread.sleep(2500)
            val flags = try { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) } catch (_: Exception) { 0 }
            val ok = (flags and FLAG_ALLOWED) == 0
            Log.i(TAG, "revokeViaRoot final: flags=$flags ok=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "revokeViaRoot failed: ${e.message}", e)
            false
        }
    }

    fun isAuthorized(uid: Int): Boolean {
        if (!isServerAlive()) return false
        val flags = runCatching { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) }.getOrDefault(0)
        return (flags and FLAG_ALLOWED) == FLAG_ALLOWED
    }
}

data class AuthorizedApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val isAllowed: Boolean,
    val isDenied: Boolean,
    val isSystemApp: Boolean
)
