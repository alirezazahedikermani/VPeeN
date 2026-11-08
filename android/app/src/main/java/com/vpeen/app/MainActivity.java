package com.vpeen.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.chaquo.python.Python;

import java.lang.ref.WeakReference;

/**
 * The whole UI is the same web app the desktop builds show (vpeen/web),
 * served by the embedded Python engine on 127.0.0.1 and displayed here in
 * a WebView.
 */
public class MainActivity extends Activity {
    private static final String TAG = "VPeeN";
    private static final int REQ_VPN = 0x7670;
    private static final int REQ_NOTIF = 0x7671;

    private static WeakReference<MainActivity> visible = new WeakReference<>(null);

    private WebView web;
    private TextView splash;

    static MainActivity visible() {
        return visible.get();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        visible = new WeakReference<>(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05070D);

        web = new WebView(this);
        web.setBackgroundColor(0xFF05070D);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost();
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                    return false;           // our own UI
                }
                try {                       // GitHub links etc. -> browser
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        web.setVisibility(android.view.View.INVISIBLE);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        splash = new TextView(this);
        splash.setText(R.string.starting);
        splash.setTextColor(Color.parseColor("#8FA3BF"));
        splash.setTextSize(16);
        splash.setGravity(Gravity.CENTER);
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));

        // targetSdk 35 is edge-to-edge: keep the UI clear of the system bars
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        startEngine();
    }

    /** Boot the Python engine off the UI thread, then load its URL. */
    private void startEngine() {
        final String filesDir = getFilesDir().getAbsolutePath();
        new Thread(() -> {
            try {
                String url = Python.getInstance().getModule("vpeen.android_main")
                        .callAttr("start", filesDir).toString();
                runOnUiThread(() -> {
                    web.loadUrl(url);
                    web.setVisibility(android.view.View.VISIBLE);
                    splash.setVisibility(android.view.View.GONE);
                });
            } catch (Throwable t) {
                Log.e(TAG, "engine start failed", t);
                runOnUiThread(() -> splash.setText("VPeeN could not start:\n" + t.getMessage()));
            }
        }, "vpeen-boot").start();
    }

    /** Show the system "allow VPN connection" dialog (from VpnBridge). */
    void requestVpnConsent(Intent consent) {
        runOnUiThread(() -> {
            try {
                startActivityForResult(consent, REQ_VPN);
            } catch (Exception e) {
                VpnBridge.onConsentResult(false);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            VpnBridge.onConsentResult(resultCode == RESULT_OK);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = new WeakReference<>(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // keep the engine (and the VPN) running; just leave the screen
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (visible.get() == this) {
            visible = new WeakReference<>(null);
        }
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
