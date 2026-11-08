package com.vpeen.app;

import android.app.Application;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/** Starts the embedded Python interpreter once per process. */
public class VPeeNApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        VpnBridge.init(this);
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
    }
}
