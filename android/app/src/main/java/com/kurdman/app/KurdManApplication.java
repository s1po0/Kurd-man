package com.kurdman.app;

import android.app.Application;
import android.os.Process;

/** Installs crash diagnostics in every app process before UI/services work. */
public class KurdManApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        KoorixDesign.initialize(this);
        DiagnosticLog.installUncaughtHandler(this);
        DiagnosticLog.info(this, "app",
                "KurdMan process started, pid=" + Process.myPid());
    }
}
