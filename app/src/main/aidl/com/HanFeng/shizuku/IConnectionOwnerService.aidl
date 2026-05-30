package com.HanFeng.shizuku;

interface IConnectionOwnerService {
    int getConnectionOwnerUid(int protocol, String localHost, int localPort, String remoteHost, int remotePort);
    void destroy();
}
