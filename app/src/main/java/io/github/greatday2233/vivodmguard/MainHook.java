package io.github.greatday2233.vivodmguard;

import android.content.ContentValues;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG = "com.vivo.devicereg";
    private static final String TAG = "[RegBlock] ";
    private static final File LOG = new File("/data/user/0/com.vivo.devicereg/cache/regblock.log");
    private static final int MIN_HITS = 10;

    private static int blocked = 0;
    private static int installed = 0;
    private static ClassLoader sCl = null;
    private static String sVer = null;

    /** 候选方法名：同一套彩讯 DM SDK 在不同 ROM/版本上命名不同，按组匹配 */
    private static final String[][] CANDIDATES = {
            {"onAppReport", "onAppReportRetry", "appInfoReport"},
            {"onHeartbeat", "onHeartbeatRetry", "onHeartbeatSuccess", "reportHeartbeat"},
            {"onNetworkCollect", "reportNetworkMsg"},
            {"onSimStateChange", "onRegisterRetry", "onRegSuccess"},
            {"onBluetoothChange", "onConnectivityChange", "onSnapshotRetry"},
            {"onBatteryChange", "onThirdRequest", "onVolteChange"},
    };

    private static void log(String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg;
        XposedBridge.log(TAG + line);
        try {
            if (LOG.length() > 256 * 1024) {
                File old = new File(LOG.getPath() + ".1");
                old.delete();
                LOG.renameTo(old);
            }
            FileWriter w = new FileWriter(LOG, true);
            w.write(line + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static int candidateCount() {
        int n = 0;
        for (String[] group : CANDIDATES) n += group.length;
        return n;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName)) return;

        ClassLoader cl = lp.classLoader;
        sCl = cl;
        log("loaded proc=" + lp.processName + " candidates=" + candidateCount());

        int hits = walk(cl, true);
        log("probe hit=" + hits + "/" + candidateCount());

        if (hits < MIN_HITS) {
            log("unsupported build (hit<" + MIN_HITS + "), no hook installed");
            return;
        }

        walk(cl, false);
        hookDb(cl);
        log("init done, installed=" + installed + " hits=" + hits);
    }

    /**
     * 遍历 DMController 的候选方法，countOnly=true 时只统计，不挂 hook。
     */
    private int walk(ClassLoader cl, boolean countOnly) {
        Class<?> controller;
        try {
            controller = XposedHelpers.findClass("cn.richinfo.dm.DMController", cl);
        } catch (Throwable t) {
            log("DMController not found: " + t);
            return 0;
        }

        java.lang.reflect.Method[] methods = controller.getDeclaredMethods();
        int hits = 0;

        for (String[] group : CANDIDATES) {
            for (String name : group) {
                for (java.lang.reflect.Method m : methods) {
                    if (!m.getName().equals(name)) continue;
                    hits++;
                    if (countOnly) continue;
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                blocked++;
                                if (sVer == null) {
                                    sVer = readVersion(sCl);
                                    log("targetVer=" + sVer);
                                }
                                log("block " + p.method.getName() + " #" + blocked);
                                p.setResult(null);
                            }
                        });
                        installed++;
                    } catch (Throwable t) {
                        log("hook fail " + m.getName() + " : " + t);
                    }
                }
            }
        }
        return hits;
    }

    private void hookDb(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("cn.richinfo.dm.db.DbManager", cl,
                    "insert", String.class, ContentValues.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("block db.insert table=" + p.args[0]);
                            p.setResult(null);
                        }
                    });
            installed++;
        } catch (Throwable t) {
            log("miss db.insert : " + t);
        }
    }

    private String readVersion(ClassLoader cl) {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", cl),
                    "currentApplication");
            if (app == null) return "unknown(no app)";
            Object pm = XposedHelpers.callMethod(app, "getPackageManager");
            Object info = XposedHelpers.callMethod(pm, "getPackageInfo", PKG, 0);
            Object vn = XposedHelpers.getObjectField(info, "versionName");
            Object vc = XposedHelpers.getObjectField(info, "versionCode");
            return vn + "(" + vc + ")";
        } catch (Throwable t) {
            return "unknown(" + t.getClass().getSimpleName() + ")";
        }
    }
}