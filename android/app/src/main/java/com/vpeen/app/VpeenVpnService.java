package com.vpeen.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.chaquo.python.Python;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * System-wide VPN: Android hands us a TUN interface, hev-socks5-tunnel
 * turns every TCP/UDP flow on it into a SOCKS5 request to the embedded
 * Python engine on 127.0.0.1, which carries it over the VeePN network.
 *
 * Loop prevention is done by excluding this app's own UID from the VPN
 * (addDisallowedApplication): the engine's upstream connections go out on
 * the real network, everybody else goes through the tunnel.
 *
 * Kill switch: if the tun2socks engine dies while the kill switch is on,
 * the TUN interface (and its routes) is KEPT, so traffic is blocked rather
 * than leaked, while we try to restart the engine.
 */
public class VpeenVpnService extends VpnService {
    static final String ACTION_START = "com.vpeen.app.START";
    static final String ACTION_DISCONNECT = "com.vpeen.app.DISCONNECT";

    private static final String TAG = "VPeeN";
    private static final String CHANNEL = "vpn";
    private static final int NOTIF_ID = 1;
    private static final String TUN_V4 = "198.18.0.1";

    static volatile VpeenVpnService instance;

    private ParcelFileDescriptor tun;
    private File configFile;
    private Thread monitor;
    private volatile boolean stopping;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_DISCONNECT.equals(action)) {
            // user tapped "Disconnect" in the notification: let the engine
            // run its normal disconnect path (it calls VpnBridge.stop()).
            new Thread(() -> {
                try {
                    Python.getInstance().getModule("vpeen.android_main")
                            .callAttr("request_disconnect");
                } catch (Throwable t) {
                    requestStop("");
                }
            }, "vpeen-notif-stop").start();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            // restarted by the system without the engine: nothing to serve
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        instance = this;
        stopping = false;
        new Thread(this::startTunnel, "vpeen-vpn-start").start();
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------ lifecycle
    private synchronized void startTunnel() {
        if (tun != null) {
            VpnBridge.report("up", "");
            return;
        }
        try {
            Builder b = new Builder()
                    .setSession("VPeeN")
                    .setMtu(VpnBridge.mtu)
                    .addAddress(TUN_V4, 32)
                    .addRoute("0.0.0.0", 0)
                    // NO IPv6 address/route on purpose: when a VPN provides
                    // no route for a family, Android BLOCKS that family (see
                    // VpnService.Builder#allowFamily), so IPv6 cannot leak -
                    // and apps never try IPv6 first (no AAAA lookups), which
                    // the IPv4-only VeePN egress cannot serve.
                    // DNS queries are relayed by the engine over the tunnel
                    // (SOCKS5 UDP ASSOCIATE, :53 only)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8");
            try {
                b.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalStateException("cannot exclude VPeeN from its own VPN", e);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                b.setMetered(false);
            }
            b.setConfigureIntent(openAppIntent());
            tun = b.establish();
            if (tun == null) {
                fail("VPN permission was revoked");
                return;
            }
            configFile = writeConfig();
            if (!TProxyService.TProxyStartService(configFile.getAbsolutePath(), tun.getFd())) {
                fail("tun2socks engine failed to start");
                return;
            }
            VpnBridge.report("up", "");
            startMonitor();
        } catch (Throwable t) {
            Log.e(TAG, "VPN start failed", t);
            fail("VPN start failed: " + t.getMessage());
        }
    }

    private File writeConfig() throws Exception {
        String yaml = "tunnel:\n"
                + "  mtu: " + VpnBridge.mtu + "\n"
                + "  ipv4: " + TUN_V4 + "\n"
                + "socks5:\n"
                + "  port: " + VpnBridge.socksPort + "\n"
                + "  address: 127.0.0.1\n"
                + "  udp: 'udp'\n"
                + "misc:\n"
                + "  task-stack-size: 81920\n";
        File f = new File(getCacheDir(), "tproxy.yml");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    /** Watches the native engine; heals it (kill switch) or reports. */
    private void startMonitor() {
        monitor = new Thread(() -> {
            long started = System.currentTimeMillis();
            boolean checked = false;
            while (!stopping) {
                if (!checked && System.currentTimeMillis() - started > 12000) {
                    checked = true;
                    trafficCheck();
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
                if (stopping || TProxyService.TProxyIsRunning()) {
                    continue;
                }
                if (!VpnBridge.killSwitch) {
                    requestStop("tun2socks engine stopped");
                    return;
                }
                // kill switch: the TUN stays up (traffic blocked) while we heal
                VpnBridge.report("ks_engaged", "");
                boolean healed = false;
                for (int i = 0; i < 3 && !stopping && !healed; i++) {
                    try {
                        Thread.sleep(1000L * (i + 1));
                    } catch (InterruptedException e) {
                        return;
                    }
                    synchronized (this) {
                        healed = tun != null && configFile != null
                                && TProxyService.TProxyStartService(
                                        configFile.getAbsolutePath(), tun.getFd());
                    }
                }
                if (stopping) {
                    return;
                }
                if (healed) {
                    VpnBridge.report("ks_healed", "");
                } else {
                    VpnBridge.report("ks_lockdown", "tunnel engine could not be restarted"
                            + " - traffic stays blocked until you disconnect");
                    return;
                }
            }
        }, "vpeen-vpn-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * One-shot self-diagnosis shortly after connect, written to the in-app
     * log: did packets enter the VPN, and did anything come back?
     */
    private void trafficCheck() {
        long[] st;
        try {
            st = TProxyService.TProxyGetStats();
        } catch (Throwable t) {
            return;
        }
        if (st == null || st.length < 4) {
            return;
        }
        // hev counters: tx = read from the TUN (apps -> VPN),
        //               rx = written to the TUN (VPN -> apps)
        long fromApps = st[0], fromAppsBytes = st[1], toApps = st[2], toAppsBytes = st[3];
        String counts = fromApps + " packets (" + fromAppsBytes / 1024 + " KB) from apps, "
                + toApps + " packets (" + toAppsBytes / 1024 + " KB) back to apps";
        if (fromApps == 0) {
            VpnBridge.report("log", "warn|Tunnel check: no app traffic entered the VPN yet ("
                    + counts + "). Open a website to test.");
        } else if (toApps == 0) {
            VpnBridge.report("log", "err|Tunnel check: apps are sending but NOTHING comes back ("
                    + counts + ") - the VeePN server is not answering; try another location.");
        } else {
            VpnBridge.report("log", "ok|Tunnel check: traffic is flowing (" + counts + ").");
        }
    }

    /** Stop everything; empty detail = normal disconnect ("down"). */
    void requestStop(String detail) {
        new Thread(() -> teardown(detail), "vpeen-vpn-stop").start();
    }

    private void fail(String detail) {
        teardown(detail.isEmpty() ? "VPN failed" : detail);
    }

    private void teardown(String detail) {
        synchronized (this) {
            if (stopping && tun == null) {
                return;
            }
            stopping = true;
            if (monitor != null) {
                monitor.interrupt();
                monitor = null;
            }
            try {
                TProxyService.TProxyStopService();
            } catch (Throwable t) {
                Log.w(TAG, "TProxyStopService", t);
            }
            if (tun != null) {
                try {
                    tun.close();
                } catch (Exception ignored) {
                }
                tun = null;
            }
        }
        if (instance == this) {
            instance = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        VpnBridge.report(detail.isEmpty() ? "down" : "error", detail);
    }

    @Override
    public void onRevoke() {
        // another VPN app took over, or the user switched VPeeN off in Settings
        teardown("VPN permission revoked by the system");
    }

    @Override
    public void onDestroy() {
        if (!stopping && tun != null) {
            teardown("VPN service was destroyed");
        }
        super.onDestroy();
    }

    // --------------------------------------------------------- notification
    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, VpeenVpnService.class).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = nb
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notif_disconnect), stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
