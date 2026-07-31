package rikka.shizuku.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    /**
     * Fork 内部使用的主客户端 permission 串。
     */
    public static final String PERMISSION = "com.HanFeng.permission.shizuku.API_V23";

    /**
     * 官方 Shizuku SDK (dev.rikka.shizuku:api / dev.rikka.shizuku:provider) 客户端
     * manifest 里声明的运行时 permission 串。第三方 APP 用 maven 拉官方 SDK 后会在
     * AndroidManifest 里写 <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />。
     *
     * fork 的 server 必须把这种客户端也认作合法客户端并推 binder 给它，否则官方 SDK APP
     * 永远收不到 binder，attachApplication 上不来，requestPermission → enforceCallingPermission
     * 链路在 requireClient 这步直接抛 IllegalStateException，授权永远卡死。
     */
    public static final String PERMISSION_LEGACY_OFFICIAL = "moe.shizuku.manager.permission.API_V23";

    /**
     * 合法的客户端 permission 串集合 —— sendBinderToClient / getApplications / updateFlagsForUid
     * 全部用本集合做"客户端打白名单"的判定，而非单串比对。
     */
    public static final List<String> CLIENT_PERMISSIONS =
            Collections.unmodifiableList(Arrays.asList(PERMISSION, PERMISSION_LEGACY_OFFICIAL));

    /**
     * 判断给定 package 的 requestedPermissions 数组里是否包含任一合法客户端 permission 串。
     */
    public static boolean isClientPermissionRequested(String[] requestedPermissions) {
        if (requestedPermissions == null) return false;
        for (String p : requestedPermissions) {
            for (String allowed : CLIENT_PERMISSIONS) {
                if (allowed != null && allowed.equals(p)) return true;
            }
        }
        return false;
    }

    // 主 app applicationId = com.HanFeng,manager 不再单独装 APK
    // server 启动时会查这个包的 ApplicationInfo 并据此认 manager、推 binder 给它
    public static final String MANAGER_APPLICATION_ID = "com.HanFeng";
    // 兼容老 REQUEST_PERMISSION_ACTION,主 app manifest 已声明同名 action 接收
    public static final String REQUEST_PERMISSION_ACTION = "com.HanFeng.intent.action.REQUEST_PERMISSION";

    public static final int BINDER_TRANSACTION_getApplications = 10001;
}
