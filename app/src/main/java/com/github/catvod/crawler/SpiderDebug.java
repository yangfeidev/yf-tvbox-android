package com.github.catvod.crawler;

import android.util.Log;

public final class SpiderDebug {
    public static void log(String message) { Log.d("Spider", message == null ? "" : message); }
    public static void log(Throwable error) { Log.w("Spider", error); }
}
