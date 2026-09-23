package com.example.scoremod;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "jp.co.sinewave.elst";
    private static final String TAG = "[scoremod] ";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {

        XposedBridge.log(TAG + "loaded in " + lpparam.packageName
                + " (process=" + lpparam.processName + ")");

        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        try {
            final ClassLoader cl = lpparam.classLoader;

            Class<?> clientCls = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient", cl);
            if (clientCls == null) {
                XposedBridge.log(TAG + "okhttp3.OkHttpClient not found (process="
                        + lpparam.processName + ")");
                return;
            }

            for (Method m : clientCls.getDeclaredMethods()) {
                if (m.getName().equals("newCall")) {
                    XposedBridge.log(TAG + "found: " + m);
                }
            }

            XposedBridge.hookAllMethods(clientCls, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 1 || param.args[0] == null) return;
                        Object newReq = rewrite(param.args[0], cl);
                        if (newReq != null) param.args[0] = newReq;
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });

            XposedBridge.log(TAG + "hook installed for " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static Object rewrite(Object req, ClassLoader cl) throws Throwable {
        if (!"POST".equals(XposedHelpers.callMethod(req, "method"))) return null;

        Object url = XposedHelpers.callMethod(req, "url");
        String path = (String) XposedHelpers.callMethod(url, "encodedPath");
        if (path == null || !path.contains("post-exercise-log")) return null;

        Object body = XposedHelpers.callMethod(req, "body");
        Class<?> multiCls = XposedHelpers.findClass("okhttp3.MultipartBody", cl);
        if (body == null || !multiCls.isInstance(body)) return null;

        Class<?> builderCls = XposedHelpers.findClass("okhttp3.MultipartBody$Builder", cl);
        Class<?> mediaTypeCls = XposedHelpers.findClass("okhttp3.MediaType", cl);
        Class<?> reqBodyCls = XposedHelpers.findClass("okhttp3.RequestBody", cl);

        Object builder = XposedHelpers.newInstance(builderCls);
        XposedHelpers.callMethod(builder, "setType", XposedHelpers.callMethod(body, "type"));

        boolean changed = false;
        for (Object part : (List<?>) XposedHelpers.callMethod(body, "parts")) {
            Object headers = XposedHelpers.callMethod(part, "headers");
            String dispo = (headers != null)
                    ? (String) XposedHelpers.callMethod(headers, "get", "Content-Disposition")
                    : null;

            String newValue = replacement(dispo);
            if (newValue == null) {
                XposedHelpers.callMethod(builder, "addPart", part);
                continue;
            }

            Object oldBody = XposedHelpers.callMethod(part, "body");
            Object contentType = XposedHelpers.callMethod(oldBody, "contentType");
            Object newBody = makeBody(reqBodyCls, mediaTypeCls, contentType, newValue);

            XposedHelpers.callMethod(builder, "addPart", headers, newBody);
            changed = true;
            XposedBridge.log(TAG + dispo + " -> " + newValue);
        }

        if (!changed) return null;

        Object newMulti = XposedHelpers.callMethod(builder, "build");
        Object reqBuilder = XposedHelpers.callMethod(req, "newBuilder");
        XposedHelpers.callMethod(reqBuilder, "post", newMulti);
        return XposedHelpers.callMethod(reqBuilder, "build");
    }

    private static String replacement(String dispo) {
        if (dispo == null) return null;
        if (dispo.contains("name=\"percent_score\"")) return "100";
        if (dispo.contains("name=\"total_score\"")) return "5";
        return null;
    }

    private static Object makeBody(Class<?> reqBodyCls, Class<?> mediaTypeCls,
                                   Object contentType, String value) throws Throwable {
        Method m = XposedHelpers.findMethodExactIfExists(reqBodyCls, "create", mediaTypeCls, byte[].class);
        if (m != null) return m.invoke(null, contentType, value.getBytes(StandardCharsets.UTF_8));

        m = XposedHelpers.findMethodExactIfExists(reqBodyCls, "create", mediaTypeCls, String.class);
        if (m != null) return m.invoke(null, contentType, value);

        m = XposedHelpers.findMethodExactIfExists(reqBodyCls, "create", String.class, mediaTypeCls);
        if (m != null) return m.invoke(null, value, contentType);

        throw new NoSuchMethodError("okhttp3.RequestBody.create(String) variant not found");
    }
}
