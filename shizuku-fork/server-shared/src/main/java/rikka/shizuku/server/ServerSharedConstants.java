package rikka.shizuku.server;

/**
 * Manager 包名常量, 在 server-shared 与 server 子模块间共享.
 *
 * 把它放在 server-shared 而非 server, 是为了让 server-shared 的 ClientManager
 * 也能识别 manager (即寒锋自己) 而无须向上反向依赖 server 子模块.
 *
 * ServerConstants (server 子模块) 里另有同名常量指向这个 String, 保持一源.
 */
public final class ServerSharedConstants {

    /**
     * server 启动时所认作 Manager 的 package name.
     * 寒锋本身既是 server 启动端又是 manager app, 自己跟 server 通信时携带的就是这个包名.
     */
    public static final String MANAGER_APPLICATION_ID = "com.HanFeng";

    private ServerSharedConstants() {}
}
