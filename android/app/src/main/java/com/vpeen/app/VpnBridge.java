package com.vpeen.app;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

import com.chaquo.python.Python;

/**
 * Glue between the Python engine (vpeen/android_tunnel.py) and the Android
 * VpnService.  Python calls {@link #start} / {@link #stop}; state changes
 * go back to Python through {@link #report}.
 */
public final class VpnBridge {
    private static final String TAG = "VPeeN";

    private static Context app;
    static volatile int socksPort = 1080;
    static volatile int mtu = 1500;
    static volatile boolean dns = true;
    static volatile boolean killSwitch = true;

    private VpnBridge() {}

    static void init(Context ctx) {
        app = ctx.getApplicationContext();
    }

    /** Called from Python: (ask for consent if needed and) start the VPN. */
    public static void start(int socks, int tunMtu, boolean tunDns, boolean ks) {
        socksPort = socks;
        mtu = Math.max(1280, Math.min(tunMtu, 9000));
        dns = tunDns;
        killSwitch = ks;

        Intent consent = VpnService.prepare(app);
        if (consent == null) {
            launchService();
            return;
        }
        MainActivity a = MainActivity.visible();
        if (a == null) {
            report("error", "VPN permission required - open VPeeN and connect again");
            return;
        }
        a.requestVpnConsent(consent);
    }

    /** Result of the system "allow VPN connection" dialog. */
    static void onConsentResult(boolean granted) {
        if (granted) {
            launchService();
        } else {
            report("error", "VPN permission was not granted");
        }
    }

    /** Called from Python: tear the VPN down. */
    public static void stop() {
        VpeenVpnService svc = VpeenVpnService.instance;
        if (svc == null) {
            report("down", "");
            return;
        }
        svc.requestStop("");
    }

    private static void launchService() {
        Intent i = new Intent(app, VpeenVpnService.class)
                .setAction(VpeenVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
        } catch (Exception e) {
            report("error", "could not start the VPN service: " + e);
        }
    }

    /** Push a state change into vpeen.android_tunnel.on_vpn_state(). */
    static void report(String phase, String detail) {
        Log.i(TAG, "vpn state " + phase + (detail.isEmpty() ? "" : " (" + detail + ")"));
        try {
            Python.getInstance().getModule("vpeen.android_tunnel")
                    .callAttr("on_vpn_state", phase, detail);
        } catch (Throwable t) {
            Log.e(TAG, "on_vpn_state failed", t);
        }
    }
}
