package com.HanFeng.shizuku;

interface IAdControlService {
    boolean ping();
    boolean blockPackageNotifications(String packageName);
    boolean allowPackageNotifications(String packageName);
    boolean disablePackage(String packageName);
    boolean enablePackage(String packageName);
    boolean disableComponent(String componentName);
    boolean enableComponent(String componentName);
    boolean suspendPackage(String packageName);
    boolean unsuspendPackage(String packageName);
    boolean setNetworkBlocked(String packageName, boolean blocked);
    boolean setBackgroundRestricted(String packageName, boolean restricted);
    boolean syncHostsBlocklist(in String[] domains);
    boolean clearHostsBlocklist();
    boolean uninstallPackageForUser(String packageName, int userId);
    boolean isPackageInstalled(String packageName);
    int getPackageEnabledState(String packageName);
    boolean isPackageSuspended(String packageName);
    String getLastOperationSummary();
    void destroy();
}
