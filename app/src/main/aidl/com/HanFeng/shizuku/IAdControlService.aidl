package com.HanFeng.shizuku;

interface IAdControlService {
    boolean ping();
    boolean blockPackageNotifications(String packageName);
    boolean allowPackageNotifications(String packageName);
    boolean disablePackage(String packageName);
    boolean enablePackage(String packageName);
    boolean suspendPackage(String packageName);
    boolean unsuspendPackage(String packageName);
    boolean uninstallPackageForUser(String packageName, int userId);
    boolean isPackageInstalled(String packageName);
    int getPackageEnabledState(String packageName);
    boolean isPackageSuspended(String packageName);
    String getLastOperationSummary();
    void destroy();
}
