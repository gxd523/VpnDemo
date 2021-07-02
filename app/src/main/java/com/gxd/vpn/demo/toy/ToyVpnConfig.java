package com.gxd.vpn.demo.toy;

import java.util.Set;

public class ToyVpnConfig {
    public final String serverHost;
    public final int serverPort;
    public final byte[] sharedSecret;
    public final String proxyHost;
    public final int proxyPort;
    /**
     * Allowed/Disallowed packages for VPN usage
     */
    public final boolean allow;
    public final Set<String> packageSet;

    public ToyVpnConfig(String serverHost, int serverPort, byte[] sharedSecret, String proxyHost, int proxyPort, boolean allow, Set<String> packageSet) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.sharedSecret = sharedSecret;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.allow = allow;
        this.packageSet = packageSet;
    }
}