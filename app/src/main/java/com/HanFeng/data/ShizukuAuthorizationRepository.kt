package com.HanFeng.data

import android.content.pm.ApplicationInfo
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
 *
 * 关键链路（与 fork 中 ShizukuService.updateFlagsForUid 一一对齐）:
 *  1) 本应用必须先拿到 Shizuku 自授权 (checkSelfPermission==GRANTED) —— 否则 server 不接受
 *     我们以 manager 身份调 updateFlagsForUid (server 端用 managerAppId==本应用 appId 校验)。
 *  2) 本应用已被 server 标为 manager (MANAGER_APPLICATION_ID == "com.HanFeng"),
 *     所以 updateFlagsForUid 调用应当通过 checkCallerManagerPermission 校验。
 *  3) 授权真正生效取决于被授权 app 的 manifest 是否声明任一合法客户端 permission:
 *       - com.HanFeng.permission.shizuku.API_V23
 *       - moe.shizuku.manager.permission.API_V23 (官方 SDK 老格式)
 *     未声明该权限的 app,server 在 sendBinderToClient 不会推 binder,
 *     第三方 app 自己的 Shizuku.checkSelfPermission() 永远返回 DENIED —— 任何客户端都无法绕过。
 */
object ShizukuAuthorizationRepository {

    private const val TAG = "ShizukuAuthz"

    fun isServerAlive(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false) ||
            runCatching { Shizuku.getBinder()?.isBinderAlive == true }.getOrDefault(false)
    }

    /**
     * 本应用是否已自我授权 (manager 自己也得有 Shizuku 权限)。
     * managerAppId 的校验在 server 端基于 Binder.getCallingUid 的 appId 比对,
     * 与本应用是否 manager 无关 —— 但本应用若没自我授权, server 不会给我们
     * 推 binder,反射 attach 不上去,所有 binder 调用都会失败。
     */
    fun selfAuthorized(): Boolean {
        if (!isServerAlive()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * 使用本地 PackageManager 列举已安装应用，通过 Shizuku binder 查询授权状态。
     */
    fun listInstalledAppsForAuth(context: android.content.Context): List<AuthorizedApp> {
        if (!selfAuthorized()) {
            Log.w(TAG, "listInstalledAppsForAuth: 本应用未获得 Shizuku 自授权,跳过 binder 查询")
            return emptyList()
        }
        return try {
            val pm = context.packageManager
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).mapNotNull { pkgInfo ->
                try {
                    val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                    val uid = appInfo.uid
                    val pkgName = pkgInfo.packageName
                    val flags = runCatching { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) }.getOrDefault(0)
                    val isAllowed = (flags and FLAG_ALLOWED) == FLAG_ALLOWED
                    // 同时检查 manifest 是否声明了客户端 permission,用作 UI 区分 "可授权" 与 "声明了客户端权限"
                    val declaredClientPerm = isClientPermissionDeclared(pkgInfo)

                    AuthorizedApp(
                        uid = uid,
                        packageName = pkgName,
                        label = pkgName,
                        icon = null,
                        isAllowed = isAllowed,
                        isDenied = !isAllowed,
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        declaresShizukuClientPermission = declaredClientPerm
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
        if (!selfAuthorized()) {
            Log.w(TAG, "grantAuthorization: 本应用未自我授权,无法执行授权操作")
            return false
        }
        return try {
            // updateFlagsForUid 在 server 端是同步调用: 一旦返回, 内存表 + 磁盘 shizuku.json
            // (configManager.update) 都已写入完成。不需要再 sleep 等异步持久化。
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
            // 紧接其后做 verify —— 此时 server.shouldRespondToBindApplication 已经把
            // 该 uid 的所有 clientRecord.allowed 标为 true。flags 持久化也已完成。
            val flags = runCatching { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) }.getOrDefault(0)
            val verified = (flags and FLAG_ALLOWED) == FLAG_ALLOWED
            Log.i(TAG, "grantAuthorization $packageName uid=$uid flags=$flags verified=$verified")
            verified
        } catch (e: Exception) {
            Log.e(TAG, "grantAuthorization failed for $packageName uid=$uid: ${e.message}", e)
            false
        }
    }

    fun revokeAuthorization(packageName: String, uid: Int): Boolean {
        if (!selfAuthorized()) {
            Log.w(TAG, "revokeAuthorization: 本应用未自我授权,无法执行取消授权操作")
            return false
        }
        return try {
            Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
            val flags = runCatching { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) }.getOrDefault(0)
            val verified = (flags and FLAG_ALLOWED) == 0
            Log.i(TAG, "revokeAuthorization $packageName uid=$uid flags=$flags verified=$verified")
            verified
        } catch (e: Exception) {
            Log.e(TAG, "revokeAuthorization failed for $packageName uid=$uid: ${e.message}", e)
            false
        }
    }

    fun isAuthorized(uid: Int): Boolean {
        if (!selfAuthorized()) return false
        val flags = runCatching { Shizuku.getFlagsForUid(uid, MASK_PERMISSION) }.getOrDefault(0)
        return (flags and FLAG_ALLOWED) == FLAG_ALLOWED
    }

    /**
     * 检测指定 app 的 manifest 是否声明了任一合法 Shizuku 客户端 permission。
     * 未声明的 app 在 server 端 sendBinderToClient 不会被推 binder,
     * 授权开关对这种 app 是无效的 —— UI 应提示用户该 app 不支持 Shizuku。
     */
    fun isClientPermissionDeclared(pkgInfo: android.content.pm.PackageInfo): Boolean {
        val requested = pkgInfo.requestedPermissions ?: return false
        for (p in requested) {
            if (p == "com.HanFeng.permission.shizuku.API_V23" ||
                p == "moe.shizuku.manager.permission.API_V23") {
                return true
            }
        }
        return false
    }
}

data class AuthorizedApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val isAllowed: Boolean,
    val isDenied: Boolean,
    val isSystemApp: Boolean,
    /** 该 app manifest 是否声明任一 Shizuku 客户端 permission。未声明的 app 授权开关无效。 */
    val declaresShizukuClientPermission: Boolean = false
)
