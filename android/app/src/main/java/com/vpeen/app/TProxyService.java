package com.vpeen.app;

/**
 * JNI entry points of hev-socks5-tunnel (tun2socks).
 *
 * The native library registers these methods on THIS class in JNI_OnLoad
 * (PKGNAME/CLSNAME are set in src/main/jni/Application.mk), so the class
 * name and signatures must match src/hev-jni.c exactly.
 */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    /** Starts the tunnel thread on {@code fd}; false if already running/failed. */
    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    /** [tx packets, tx bytes, rx packets, rx bytes]. */
    public static native long[] TProxyGetStats();
}
