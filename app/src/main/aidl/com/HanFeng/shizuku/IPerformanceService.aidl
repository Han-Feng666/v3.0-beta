package com.HanFeng.shizuku;

interface IPerformanceService {
    boolean ping();
    String getForegroundPackage();
    String[] getRunningPackages();
    String readProcessStat(int pid);
    String dumpProcessInfo();
    void destroy();
}
