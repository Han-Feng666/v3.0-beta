package moe.shizuku.manager.authorization

import android.content.pm.PackageInfo
import android.os.Parcel
import android.util.Log
import rikka.shizuku.server.ServerConstants
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku

/**
 * 简化版：只支持 Shizuku v11+。
 * 我们已 fork 为 com.HanFeng.shizuku，过滤自己的 packageName。
 *
 * 安全加固：
 *   - 所有 binder 入口都先拿 Binder 并 null-check；binder 不可用时抛 IllegalState 而非 NPE。
 *   - getApplications 在 binder 失联/返回 null 时返回 emptyList — 调用方不必处理 NPE。
 */
object AuthorizationManager {

    private const val TAG = "AuthzManager"
    private const val SELF_PACKAGE_NAME = "com.HanFeng.shizuku"

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private fun getApplications(userId: Int): List<PackageInfo> {
        val binder = Shizuku.getBinder() ?: run {
            Log.w(TAG, "getApplications: binder unavailable")
            return emptyList()
        }
        if (!binder.isBinderAlive) {
            Log.w(TAG, "getApplications: binder not alive")
            return emptyList()
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(userId)
            binder.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            val slice = ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>
            slice.list ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getApplications transact failed: ${e.message}", e)
            emptyList()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getPackages(): List<PackageInfo> {
        val packages = getApplications(-1)
        if (packages.isEmpty()) return emptyList()
        return packages.filter { it.packageName != SELF_PACKAGE_NAME }
    }

    fun granted(packageName: String, uid: Int): Boolean {
        return try {
            (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "granted failed for uid=$uid: ${e.message}")
            false
        }
    }

    fun grant(packageName: String, uid: Int) {
        Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
    }

    fun revoke(packageName: String, uid: Int) {
        Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_DENIED)
    }
}
