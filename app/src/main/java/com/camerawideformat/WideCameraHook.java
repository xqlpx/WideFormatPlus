package com.camerawideformat;

import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WideCameraHook implements IXposedHookLoadPackage {

    private static final String TAG = "WideCamera";
    private static final String PKG = "com.oplus.camera";
    private static final String WIDE = "wide";
    private static final String RATIO_KEY = "pref_camera_photo_ratio_key";
    private static final String WIDE_MODELIST = "com.oplus.camera.wide.frame.ratio.support.modelist";
    // Keep "wide" visible in the menu, but hand the stream/size layer a
    // HAL-supported ratio so preview can actually be configured.
    private static final String RATIO_FALLBACK = "full";
    // 65:24 XPAN target ratio (7872 / 2912)
    private static final double TARGET_RATIO = 7872.0 / 2912.0;
    // Watermark switches consulted by the picture pipeline. The capture
    // pipeline reads pref_watermark_function_key / the Hasselblad one, while
    // the shortcut panel reads the capture_switch key; all of them gate the
    // saved frame, so while the wide band is active every one of them answers
    // false and the photo ships clean. Other ratios read through untouched.
    private static final java.util.Set<String> WATERMARK_KEYS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "pref_watermark_function_key",
                    "pref_hasselblad_watermark_function_key",
                    "pref_watermark_capture_switch_key",
                    "pref_watermark_gr_capture_switch_key",
                    "pref_watermark_makeup_function_key",
                    "pref_ai_master_watermark_photo_open_state",
                    "pref_ai_master_watermark_mode_limit_open_state"));

    /**
     * DataManager.b(DataKey, default) hands back the key's declared type, so
     * the replacement has to match it: a Boolean switch wants FALSE, a style
     * string wants the "off" token. Mirror the caller's default so answering
     * "no watermark" never throws a ClassCastException back in the camera.
     */
    private static Object watermarkOffValue(Object def) {
        if (def instanceof Boolean) {
            return Boolean.FALSE;
        }
        if (def instanceof Integer) {
            return Integer.valueOf(0);
        }
        if (def instanceof String) {
            return "off";
        }
        return Boolean.FALSE;
    }

    private Object mRatioDataKey = null;
    private volatile boolean mRatioKeyResolved = false;
    private volatile boolean mWideActive = false;
    private volatile long mLoadTime = 0L;
    private volatile boolean mPostProcStarted = false;
    private final java.util.Map<String, Long> mLastSize =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    // Poll cadence. The post-processor only has work to do while a wide shot
    // is in flight or just finished; the rest of the time it must cost as
    // close to nothing as possible. Android keeps the camera process cached
    // long after the user leaves the app, so the idle path skips the DCIM
    // scan entirely and parks on a sleep that any wide signal can cut short.
    private static final long ACTIVE_POLL_MS = 700L;
    private static final long IDLE_POLL_MS = 3000L;
    private static final long WIDE_GRACE_MS = 60 * 1000L;
    private final Object mWakeLock = new Object();

    /**
     * Cut the post-processor's current sleep short so a fresh wide signal is
     * acted on immediately instead of waiting out the idle interval.
     */
    private void wakePostProcessor() {
        synchronized (mWakeLock) {
            mWakeLock.notifyAll();
        }
    }

    private void sleepPostProcessor(long ms) {
        synchronized (mWakeLock) {
            try {
                mWakeLock.wait(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Preview framing. The capture chain is settled; this is the other half of
     * "what you see is what you get": the viewfinder itself has to show 65:24
     * or the framing you shoot is a guess.
     *
     * The preview stream stays at the HAL-supported ratio (native 65:24 stream
     * config is a dead end on this device). What changes is the display region:
     * the preview surface is GLRootView, and while wide is active its view is
     * letterboxed down to the wide ratio, centred, so the visible band matches
     * the final centre-crop. Every other ratio restores full-bleed.
     */
    private volatile java.lang.ref.WeakReference<android.view.SurfaceView> mGlRootView =
            new java.lang.ref.WeakReference<android.view.SurfaceView>(null);

    // Letterbox masks. The preview surface stays full-bleed so the camera keeps
    // rendering the way it always does; these two plain Views paint the black
    // bars and swallow touch, so a tap on the black area no longer reaches the
    // camera UI underneath.
    private volatile java.lang.ref.WeakReference<android.view.View> mMaskLeft =
            new java.lang.ref.WeakReference<android.view.View>(null);
    private volatile java.lang.ref.WeakReference<android.view.View> mMaskRight =
            new java.lang.ref.WeakReference<android.view.View>(null);
    // Last band width we logged, so the log fires on change instead of on
    // visibility flips (a freshly built mask is already VISIBLE).
    private volatile int mLoggedBandW = -1;

    private android.view.View ensureMask(android.view.ViewGroup parent, boolean left,
                                         android.view.SurfaceView ref) {
        android.view.View mask = (left ? mMaskLeft : mMaskRight).get();
        if (mask != null && mask.getParent() == parent) {
            // Keep the bar just above the preview but below every control that
            // was added later, in case an older build parked it on top.
            int want = parent.indexOfChild(ref) + 1;
            int cur = parent.indexOfChild(mask);
            if (cur > want) {
                android.view.ViewGroup.LayoutParams lp = mask.getLayoutParams();
                if (lp != null) {
                    parent.removeView(mask);
                    parent.addView(mask, want, lp);
                }
            }
            return mask;
        }
        mask = new android.view.View(ref.getContext());
        mask.setBackgroundColor(0xFF000000);
        mask.setClickable(true);
        mask.setFocusable(false);
        mask.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View view, android.view.MotionEvent event) {
                return true;
            }
        });
        android.view.ViewGroup.LayoutParams mlp;
        if (parent instanceof android.widget.FrameLayout) {
            mlp = new android.widget.FrameLayout.LayoutParams(0, 0);
        } else if (parent instanceof android.widget.RelativeLayout) {
            mlp = new android.widget.RelativeLayout.LayoutParams(0, 0);
        } else {
            mlp = new android.view.ViewGroup.LayoutParams(0, 0);
        }
        // Sit the bar directly above the preview surface but below every
        // control the camera adds after it, so the black bar never covers or
        // steals touches from the shutter, zoom bar and friends.
        int insertAt = parent.indexOfChild(ref) + 1;
        if (insertAt > 0 && insertAt <= parent.getChildCount()) {
            parent.addView(mask, insertAt, mlp);
        } else {
            parent.addView(mask, mlp);
        }
        if (left) {
            mMaskLeft = new java.lang.ref.WeakReference<android.view.View>(mask);
        } else {
            mMaskRight = new java.lang.ref.WeakReference<android.view.View>(mask);
        }
        return mask;
    }

    private void applyMaskBounds(android.view.View mask, int x, int y, int w, int h) {
        android.view.ViewGroup.LayoutParams lp = mask.getLayoutParams();
        if (lp == null) {
            return;
        }
        w = Math.max(0, w);
        h = Math.max(0, h);
        if (lp.width == w && lp.height == h) {
            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams flp =
                        (android.widget.FrameLayout.LayoutParams) lp;
                if (flp.leftMargin == x && flp.topMargin == y) {
                    return;
                }
            } else if (lp instanceof android.widget.RelativeLayout.LayoutParams) {
                android.widget.RelativeLayout.LayoutParams rlp =
                        (android.widget.RelativeLayout.LayoutParams) lp;
                if (rlp.leftMargin == x && rlp.topMargin == y) {
                    return;
                }
            } else {
                return;
            }
        }
        lp.width = w;
        lp.height = h;
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            ((android.widget.FrameLayout.LayoutParams) lp).leftMargin = x;
            ((android.widget.FrameLayout.LayoutParams) lp).topMargin = y;
        } else if (lp instanceof android.widget.RelativeLayout.LayoutParams) {
            ((android.widget.RelativeLayout.LayoutParams) lp).leftMargin = x;
            ((android.widget.RelativeLayout.LayoutParams) lp).topMargin = y;
        }
        mask.setLayoutParams(lp);
    }

    /**
     * XPan is 65:24. The preview is a 4:3 frame drawn upright and centre-cropped
     * to fill this portrait panel, so part of the sensor's short edge is already
     * gone before we ever see it; the wide slice that survives is 24/65 of the
     * screen height, centred, with the black bars left and right. The surface is
     * never resized — shrinking it changed how the camera scaled the preview,
     * which is why the band never lined up with the capture.
     */
    private void updatePreviewMasks(android.view.SurfaceView v, boolean wide,
                                    int screenW) {
        android.view.ViewParent p = v.getParent();
        if (!(p instanceof android.view.ViewGroup)) {
            return;
        }
        android.view.ViewGroup vg = (android.view.ViewGroup) p;
        android.view.View left = ensureMask(vg, true, v);
        android.view.View right = ensureMask(vg, false, v);
        int vgW = vg.getWidth() > 0 ? vg.getWidth() : screenW;
        int vgH = vg.getHeight() > 0 ? vg.getHeight()
                : v.getResources().getDisplayMetrics().heightPixels;
        int bandW = (int) Math.round(vgH * 24.0 / 65.0);
        if (bandW > vgW) {
            bandW = vgW;
        }
        int barW = Math.max(0, (vgW - bandW) / 2);
        if (wide && barW > 0) {
            applyMaskBounds(left, 0, 0, barW, vgH);
            applyMaskBounds(right, vgW - barW, 0, barW, vgH);
            left.setVisibility(android.view.View.VISIBLE);
            right.setVisibility(android.view.View.VISIBLE);
            if (mLoggedBandW != bandW) {
                mLoggedBandW = bandW;
                log("preview band ON " + bandW + "x" + vgH + " bars=" + barW);
            }
        } else {
            left.setVisibility(android.view.View.GONE);
            right.setVisibility(android.view.View.GONE);
            if (mLoggedBandW != -1) {
                mLoggedBandW = -1;
                log("preview band OFF");
            }
        }
    }

    /**
     * Wide shots ship without a watermark, whatever the watermark switch says.
     * The switch is a plain boolean read through DataManager.b(DataKey, def);
     * while the wide band is active this answers false for the capture
     * watermark key only, so every other ratio keeps following the user's
     * setting. This is the read the picture pipeline consults, not the menu
     * state, so the setting itself stays where the user put it.
     */
    /**
     * DataKey keeps its key name in one of several final String fields, and the
     * obfuscator shuffles which one. Read the candidates defensively so the
     * watermark probe does not depend on a single field surviving the mapping.
     */
    private static String readKeyString(Object key, String field) {
        try {
            Object v = key.getClass().getField(field).get(key);
            return v == null ? null : v.toString();
        } catch (Throwable t1) {
            try {
                java.lang.reflect.Field f = key.getClass().getDeclaredField(field);
                f.setAccessible(true);
                Object v = f.get(key);
                return v == null ? null : v.toString();
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    /**
     * DataKey instances are static singletons, so the reflective field read is
     * a one-off per key. Weak keys keep the cache from pinning anything if the
     * camera ever builds a throwaway key at runtime.
     */
    private static final java.util.Map<Object, String[]> sKeyFields =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** The {f, d, e} name candidates for this DataKey, resolved once. */
    private static String[] keyFields(Object key) {
        String[] cached = sKeyFields.get(key);
        if (cached != null) {
            return cached;
        }
        String[] fields = new String[]{
                readKeyString(key, "f"),
                readKeyString(key, "d"),
                readKeyString(key, "e")
        };
        sKeyFields.put(key, fields);
        return fields;
    }

    /** True when this DataKey is one of the switches that gate the watermark. */
    private static boolean isWatermarkKey(Object key) {
        for (String name : keyFields(key)) {
            if (name != null && WATERMARK_KEYS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** Resolve the ratio DataKey even when no ratio read has fired yet, so the
     *  watermark gate can still tell RICOH GR is sitting on the wide band. */
    private void resolveRatioKey(final XC_LoadPackage.LoadPackageParam lp) {
        if (mRatioDataKey != null || mRatioKeyResolved) {
            return;
        }
        mRatioKeyResolved = true;
        try {
            java.lang.reflect.Field bField = lp.classLoader
                    .loadClass("wa.e").getDeclaredField("b");
            bField.setAccessible(true);
            mRatioDataKey = bField.get(null);
            log("resolved wa.e.b ratio DataKey (eager)");
        } catch (Throwable t) {
            log("eager resolve wa.e.b FAILED: " + t);
        }
    }

    private void hookWatermarkSwitch(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> dataKeyClass = lp.classLoader.loadClass("com.oplus.camera.data.DataKey");
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.data.DataManager", lp.classLoader,
                    "b", dataKeyClass, Object.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object key = param.args[0];
                                if (key == null) {
                                    return;
                                }
                                String name = keyFields(key)[0];
                                if (name != null && name.contains("watermark")) {
                                    log("wm probe b " + name + " wide=" + mWideActive
                                            + " val=" + param.getResult());
                                    if (mWideActive || isWideSelected(lp)) {
                                        StringBuilder st = new StringBuilder();
                                        try {
                                            StackTraceElement[] tr = Thread.currentThread().getStackTrace();
                                            for (int si = 0; si < tr.length && si < 12; si++) {
                                                st.append(tr[si].getClassName()).append('#')
                                                        .append(tr[si].getMethodName()).append(' ');
                                            }
                                        } catch (Throwable ignored3) {
                                        }
                                        log("wm caller b " + name + ": " + st);
                                    }
                                }
                                if (!isWatermarkKey(key)) {
                                    return;
                                }
                                boolean wide = mWideActive || isWideSelected(lp);
                                if (!wide) {
                                    return;
                                }
                                param.setResult(watermarkOffValue(param.getResult()));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.data.DataManager", lp.classLoader,
                    "c", dataKeyClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object key = param.args[0];
                                if (key == null) {
                                    return;
                                }
                                String name = keyFields(key)[0];
                                if (name != null && name.contains("watermark")) {
                                    log("wm probe c " + name + " wide=" + mWideActive
                                            + " val=" + param.getResult());
                                    if (mWideActive || isWideSelected(lp)) {
                                        StringBuilder st = new StringBuilder();
                                        try {
                                            StackTraceElement[] tr = Thread.currentThread().getStackTrace();
                                            for (int si = 0; si < tr.length && si < 12; si++) {
                                                st.append(tr[si].getClassName()).append('#')
                                                        .append(tr[si].getMethodName()).append(' ');
                                            }
                                        } catch (Throwable ignored3) {
                                        }
                                        log("wm caller c " + name + ": " + st);
                                    }
                                }
                                if (!isWatermarkKey(key)) {
                                    return;
                                }
                                boolean wide = mWideActive || isWideSelected(lp);
                                if (!wide) {
                                    return;
                                }
                                param.setResult(watermarkOffValue(param.getResult()));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("hooked DataManager.b (wide watermark off)");
        } catch (Throwable t) {
            log("hook DataManager.b FAILED: " + t);
        }
    }

    private void hookPreviewCrop(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.ui.preview.glview.GLRootView", lp.classLoader,
                    "onAttachedToWindow", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            mGlRootView = new java.lang.ref.WeakReference<android.view.SurfaceView>(
                                    (android.view.SurfaceView) param.thisObject);
                            log("preview crop: captured GLRootView");
                            applyPreviewCrop();
                        }
                    });
            log("hooked GLRootView.onAttachedToWindow");
        } catch (Throwable t) {
            log("hook GLRootView.onAttachedToWindow FAILED: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.ui.preview.glview.GLRootView", lp.classLoader,
                    "b", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // b() sets the surface back to full screen; drop the
                            // cached layout height so applyPreviewCrop re-runs its
                            // change detection instead of short-circuiting and
                            // leaving the view squashed over a full-size buffer.
                            android.view.SurfaceView sv = (mGlRootView == null)
                                    ? null : mGlRootView.get();
                            if (sv != null) {
                                android.view.ViewGroup.LayoutParams lp = sv.getLayoutParams();
                                if (lp != null) {
                                    lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                                }
                            }
                            applyPreviewCrop();
                        }
                    });
            log("hooked GLRootView.b");
        } catch (Throwable t) {
            log("hook GLRootView.b FAILED: " + t);
        }
    }

    private void applyPreviewCrop() {
        final android.view.SurfaceView v = (mGlRootView == null) ? null : mGlRootView.get();
        if (v == null) {
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                    if (lp == null) {
                        return;
                    }
                    // Carve the wide framing out with masks instead of resizing
                    // the surface. Shrinking GLRootView changes how the camera
                    // scales the preview into it, so the band never matched the
                    // capture. A full-bleed surface keeps the camera's own
                    // rendering untouched; the masks decide the visible slice.
                    if (lp.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            || lp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                        lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                        v.setLayoutParams(lp);
                        v.requestLayout();
                    }
                    updatePreviewMasks(v, mWideActive,
                            v.getResources().getDisplayMetrics().widthPixels);
                } catch (Throwable t) {
                    log("applyPreviewCrop error: " + t);
                }
            }
        });
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) {
            return;
        }
        mLoadTime = System.currentTimeMillis();
        log("loaded into " + lpparam.packageName + " process=" + lpparam.processName);
        hookCameraConfigX(lpparam);
        hookI0(lpparam);
        hookSetOptionItemsVisible(lpparam);
        hookSetOptionItems(lpparam);
        hookGnNJ(lpparam);
        hookAnBR(lpparam);
        hookModeTracker(lpparam);
        hookRatioGetter(lpparam);
        hookXpanConfigGate(lpparam);
        hookXpanDrawableFallback(lpparam);
        hookBitmapCompress(lpparam);
        hookFileOutputStreamWrite(lpparam);
        hookFileChannelWrite(lpparam);
        hookKcM1G(lpparam);
        hookPreviewCrop(lpparam);
        hookWatermarkSwitch(lpparam);
        startPostProcessor();
    }

    // Current camera mode name ("common", "xpan", ...). While XPAN is active
    // the module stands down completely: every ratio/option tweak below targets
    // the normal photo pipeline and would only confuse the self-contained XPAN
    // mode, which is exactly what left the UI unresponsive.
    private volatile String mCurrentMode = "";

    private void hookModeTracker(final XC_LoadPackage.LoadPackageParam lp) {
        boolean installed = false;
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.feature.arch.mvp.a0", lp.classLoader,
                    "getCurrentModeName",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            if (r instanceof String) {
                                mCurrentMode = (String) r;
                            }
                        }
                    });
            installed = true;
        } catch (Throwable t) {
            log("mode tracker: a0.getCurrentModeName FAILED: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.entry.CameraEntry", lp.classLoader,
                    "j",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            if (r instanceof String) {
                                mCurrentMode = (String) r;
                            }
                        }
                    });
            installed = true;
        } catch (Throwable t) {
            log("mode tracker: CameraEntry.j FAILED: " + t);
        }
        log("mode tracker installed=" + installed);
    }

    private boolean isXpanMode() {
        return "xpan".equals(mCurrentMode);
    }

    private void hookCameraConfigX(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.configure.CameraConfig", lp.classLoader,
                    "x", String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object[] a = param.args;
                            if (a != null && a.length == 2 && WIDE_MODELIST.equals(a[1])) {
                                log("CameraConfig.x(" + a[0] + ", " + a[1] + ") -> forced true");
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            log("hooked CameraConfig.x");
        } catch (Throwable t) {
            log("hook CameraConfig.x FAILED: " + t);
        }
    }

    private void hookI0(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "u7.o0", lp.classLoader,
                    "i0", String.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isXpanMode()) {
                                return;
                            }
                            log("u7.o0.i0(" + Arrays.toString(param.args) + ") -> forced true");
                            param.setResult(Boolean.TRUE);
                        }
                    });
            log("hooked u7.o0.i0");
        } catch (Throwable t) {
            log("hook u7.o0.i0 FAILED: " + t);
        }
    }

    private void hookSetOptionItemsVisible(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "gl.b", lp.classLoader,
                    "setOptionItemsVisible", String[].class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isXpanMode()) {
                                return;
                            }
                            String[] values = (String[]) param.args[0];
                            boolean visible = (Boolean) param.args[1];
                            log("gl.b.setOptionItemsVisible(" + Arrays.toString(values) + ", visible=" + visible + ")");
                            if (!visible && contains(values, WIDE)) {
                                param.args[1] = Boolean.TRUE;
                                log("  -> forced visible=true for wide");
                            }
                        }
                    });
            log("hooked gl.b.setOptionItemsVisible");
        } catch (Throwable t) {
            log("hook gl.b.setOptionItemsVisible FAILED: " + t);
        }
    }

    private void hookSetOptionItems(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "gl.b", lp.classLoader,
                    "setOptionItems", java.util.ArrayList.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object items = param.args[0];
                            int size = (items instanceof java.util.List) ? ((java.util.List<?>) items).size() : -1;
                            Object key = "?";
                            try {
                                key = XposedHelpers.callMethod(param.thisObject, "getOptionKey");
                            } catch (Throwable ignored) {
                            }
                            log("gl.b.setOptionItems key=" + key + " size=" + size);
                            if (key != null && key.toString().contains("watermark")) {
                                log("  wm option class=" + param.thisObject.getClass().getName()
                                        + " xpan=" + isXpanMode());
                            }
                        }
                    });
            log("hooked gl.b.setOptionItems");
        } catch (Throwable t) {
            log("hook gl.b.setOptionItems FAILED: " + t);
        }
    }

    private void hookGnNJ(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "gn.n", lp.classLoader,
                    "J", String.class, String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            log("gn.n.J(" + param.args[0] + ", " + Arrays.toString((String[]) param.args[1]) + ")");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            log("gn.n.J -> " + param.getResult());
                        }
                    });
            log("hooked gn.n.J");
        } catch (Throwable t) {
            log("hook gn.n.J FAILED: " + t);
        }
    }

    private void hookAnBR(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "an.b", lp.classLoader,
                    "R", String.class, String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isXpanMode()) {
                                return;
                            }
                            String key = (String) param.args[0];
                            String[] values = (String[]) param.args[1];
                            log("an.b.R(" + key + ", " + Arrays.toString(values) + ")");
                            if (RATIO_KEY.equals(key) && contains(values, WIDE)) {
                                log("  -> blocked wide-hide in an.b.R");
                                param.setResult(null);
                            }
                        }
                    });
            log("hooked an.b.R");
        } catch (Throwable t) {
            log("hook an.b.R FAILED: " + t);
        }
    }

    /**
     * Central ratio accessor: u7.q0.b(DataKey, mode, default) -> String.
     * wa.d.n1() reads the active photo ratio through this. When the stored
     * value is "wide" we hand back a HAL-supported ratio so the preview / picture
     * stream can actually be configured on devices that lack a native 65:24 size.
     */
    /**
     * Held so the post-processor, which runs on its own thread with no
     * LoadPackageParam of its own, can still query the camera's live ratio
     * preference instead of trusting a flag that flips on transient reads.
     */
    private static volatile XC_LoadPackage.LoadPackageParam sLp = null;

    private void hookRatioGetter(final XC_LoadPackage.LoadPackageParam lp) {
        sLp = lp;
        try {
            Class<?> dataKeyClass = lp.classLoader.loadClass("com.oplus.camera.data.DataKey");
            XposedHelpers.findAndHookMethod(
                    "u7.q0", lp.classLoader,
                    "b", dataKeyClass, String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length > 1 && param.args[1] instanceof String) {
                                    mCurrentMode = (String) param.args[1];
                                }
                                if (isXpanMode()) {
                                    return;
                                }
                                Object result = param.getResult();
                                if (!(result instanceof String)) {
                                    return;
                                }
                                if (WIDE.equals(result)) {
                                    mWideActive = true;
                                    mWideLastSeenAt = System.currentTimeMillis();
                                    applyPreviewCrop();
                                    // Pull the post-processor out of its idle
                                    // sleep right away so the first frame of a
                                    // wide session is never missed just because
                                    // the thread happened to be parked.
                                    wakePostProcessor();
                                    if (isRatioKey(param.args[0], lp)) {
                                        log("u7.q0.b ratio=wide -> map to " + RATIO_FALLBACK
                                                + " (mode=" + param.args[1] + ")");
                                        param.setResult(RATIO_FALLBACK);
                                    }
                                } else if (isRatioKey(param.args[0], lp)) {
                                    mWideActive = false;
                                    applyPreviewCrop();
                                }
                            } catch (Throwable t) {
                                log("q0.b after-hook error: " + t);
                            }
                        }
                    });
            log("hooked u7.q0.b");
        } catch (Throwable t) {
            log("hook u7.q0.b FAILED: " + t);
        }
    }

    /**
     * XPAN (native 65:24 wide) ships inside the OPPO camera but is hidden
     * behind the config flag "com.oplus.feature.xpan.mode.support". Force
     * that one key to true so the mode becomes selectable in the mode list.
     * The rest of the config surface is left untouched.
     */
    private void hookXpanConfigGate(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.configure.CameraConfig", lp.classLoader,
                    "getConfigBooleanValue", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object k = param.args[0];
                                if ("com.oplus.feature.xpan.mode.support".equals(k)) {
                                    if (!Boolean.TRUE.equals(param.getResult())) {
                                        log("xpan gate forced true (was "
                                                + param.getResult() + ")");
                                    }
                                    param.setResult(Boolean.TRUE);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            log("hooked CameraConfig.getConfigBooleanValue (xpan gate)");
        } catch (Throwable t) {
            log("hook xpan gate FAILED: " + t);
        }
    }

    /**
     * XPAN's UI references several drawables whose layer-list XML points at
     * sub-drawables that were stripped from this device's camera build. Loading
     * one throws Resources$NotFoundException inside XPanExposureWheel.<init>,
     * which unwinds XPanPresenter.onCreate and kills the activity before the
     * mode ever reaches the HAL. Intercept the resource entry points and, for
     * any resource whose simple name starts with "xpan", answer with a
     * transparent drawable instead of letting the loader parse the broken XML.
     * Everything else passes straight through untouched.
     */
    private void hookXpanDrawableFallback(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.res.Resources", lp.classLoader, "getDrawableForDensity",
                    int.class, int.class, android.content.res.Resources.Theme.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.graphics.drawable.Drawable d =
                                    xpanFallback(param.thisObject, (Integer) param.args[0]);
                            if (d != null) {
                                param.setResult(d);
                            }
                        }
                    });
            log("hooked Resources.getDrawableForDensity (xpan bypass)");
        } catch (Throwable t) {
            log("hook getDrawableForDensity FAILED: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.res.Resources", lp.classLoader, "loadDrawable",
                    android.util.TypedValue.class, int.class, int.class,
                    android.content.res.Resources.Theme.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.graphics.drawable.Drawable d =
                                    xpanFallback(param.thisObject, (Integer) param.args[1]);
                            if (d != null) {
                                param.setResult(d);
                            }
                        }
                    });
            log("hooked Resources.loadDrawable (xpan bypass)");
        } catch (Throwable t) {
            log("hook loadDrawable FAILED: " + t);
        }
    }

    /**
     * Returns a transparent placeholder for xpan* drawables, or null when the
     * resource is not part of the XPAN family and must be loaded normally.
     */
    /**
     * Only these drawables are known to have broken layer-list XML on this
     * build. Everything else named xpan_* loads normally, so the mode picker
     * still gets real, correctly-sized icons.
     */
    private static final java.util.Set<String> XPAN_BROKEN_DRAWABLES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "xpan_triangle_indicator_with_shadow"));

    private android.graphics.drawable.Drawable xpanFallback(Object resObj, int id) {
        try {
            android.content.res.Resources res = (android.content.res.Resources) resObj;
            String name = res.getResourceName(id);
            if (name == null) {
                return null;
            }
            String simple = name.substring(name.lastIndexOf('/') + 1);
            if (!XPAN_BROKEN_DRAWABLES.contains(simple)) {
                return null;
            }
            log("xpan drawable bypassed: " + name + " (0x" + Integer.toHexString(id) + ")");
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            return new android.graphics.drawable.BitmapDrawable(res, bmp);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isRatioKey(Object key, XC_LoadPackage.LoadPackageParam lp) {
        if (key == null) {
            return false;
        }
        // Preferred hit: the exact ratio DataKey resolved from wa.e.b.
        if (mRatioDataKey != null && key == mRatioDataKey) {
            return true;
        }
        if (!mRatioKeyResolved) {
            mRatioKeyResolved = true;
            try {
                java.lang.reflect.Field bField = lp.classLoader
                        .loadClass("wa.e").getDeclaredField("b");
                bField.setAccessible(true);
                mRatioDataKey = bField.get(null);
                log("lazily resolved wa.e.b ratio DataKey");
            } catch (Throwable t) {
                log("lazy resolve wa.e.b FAILED: " + t);
            }
        }
        if (mRatioDataKey != null && key == mRatioDataKey) {
            return true;
        }
        // Name fallback, applied even when wa.e.b *did* resolve. The camera can
        // route the ratio read through a different DataKey instance (front/back
        // lens, mode switch, relaunch), and the old "reference only" check then
        // returned false for the live ratio key. That left mWideActive stuck at
        // true after any "wide" read, so the next launch cropped a 4:3 shot to
        // 65:24. The suffix stays strict (photo_ratio_key) to avoid the old
        // loose-match regression.
        String name = readKeyName(key);
        return name != null && (name.equals(RATIO_KEY) || name.endsWith("photo_ratio_key"));
    }

    private static final ThreadLocal<Boolean> mInCrop =
            new ThreadLocal<Boolean>();

    /**
     * {x, y, w, h} of the centred 65:24 crop for a w x h frame, or null when
     * the frame is too small or already at the target ratio. The
     * orientation-aware maths lives here so the three crop paths (byte[] write,
     * kc.m1 bitmap, Bitmap.compress) cannot drift apart.
     */
    private static int[] centredWideCrop(int w, int h) {
        if (w < 1500) {
            return null;
        }
        double target = (h > w) ? (1.0 / TARGET_RATIO) : TARGET_RATIO;
        double ratio = (double) w / (double) h;
        if (Math.abs(ratio - target) < 0.03) {
            return null;
        }
        int nw, nh;
        if (ratio > target) {
            nh = h;
            nw = (int) Math.round(h * target);
        } else {
            nw = w;
            nh = (int) Math.round(w / target);
        }
        return new int[]{(w - nw) / 2, (h - nh) / 2, nw, nh};
    }

    /**
     * Centre-crop a JPEG byte[] to 65:24 while "wide" is active. Returns the
     * original array untouched if it is not a large JPEG or already 65:24.
     */
    private static byte[] cropJpegIfWide(byte[] data) {
        if (data == null || data.length < 300000) {
            return data;
        }
        if (data.length < 3 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return data;
        }
        if (Boolean.TRUE.equals(mInCrop.get())) {
            return data;
        }
        try {
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            int w = opts.outWidth;
            int h = opts.outHeight;
            int[] crop = centredWideCrop(w, h);
            if (crop == null) {
                return data;
            }
            mInCrop.set(Boolean.TRUE);
            android.graphics.Bitmap src =
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
            if (src == null) {
                return data;
            }
            int x = crop[0];
            int y = crop[1];
            int nw = crop[2];
            int nh = crop[3];
            android.graphics.Bitmap cropped =
                    android.graphics.Bitmap.createBitmap(src, x, y, nw, nh);
            java.io.ByteArrayOutputStream baos =
                    new java.io.ByteArrayOutputStream(data.length);
            cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, baos);
            byte[] out = baos.toByteArray();
            byte[] withExif = spliceJpeg(data, out, nw, nh);
            log("cropped JPEG " + w + "x" + h + " -> " + nw + "x" + nh
                    + " (65:24) exif=" + (withExif != out));
            return withExif;
        } catch (Throwable t) {
            log("cropJpegIfWide error: " + t);
            return data;
        } finally {
            mInCrop.remove();
        }
    }

    private static byte[] readAllBytes(java.io.File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            long len = f.length();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                    len > 0 ? (int) len : 8192);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Keep every metadata segment the camera wrote and only swap the pixels.
     * Bitmap.compress emits a bare JFIF header, so re-attaching EXIF tag by tag
     * drops XMP, MakerNote and OPPO's private segments; the gallery keys off
     * those to decide whether a RICOH watermark can be added later. Instead,
     * copy the original's APPn/COM segments verbatim and take the compressed
     * frame (DQT/SOF/DHT/SOS...) from the cropped JPEG.
     *
     * v63: the shooting-parameter watermark (aperture / shutter / ISO / focal
     * length) reads the very same OPPO-private EXIF block, not just IFD0
     * Make/Model. So the original APP1 must be carried over whole via
     * copyPatchedExif() instead of being re-emitted through ExifInterface —
     * the white-list path keeps only Make/Model and leaves the parameter
     * fields blank.
     */
    private static byte[] spliceJpeg(byte[] original, byte[] cropped, int nw, int nh) {
        try {
            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream(original.length + cropped.length);
            out.write(0xFF);
            out.write(0xD8);
            // The encoder's own APP block is deliberately left behind: it is
            // an Ultra-HDR gain-map set (XMP + ICC + GContainer + MPF) that
            // would otherwise sit ahead of the camera's XMP and make the file
            // read like a multi-picture capture. Only JFIF is carried over, so
            // the segment layout ends up the same kind as a camera-native JPEG.
            if (cropped.length > 6 && (cropped[2] & 0xFF) == 0xFF
                    && (cropped[3] & 0xFF) == 0xE0) {
                int jfifLen = ((cropped[4] & 0xFF) << 8) | (cropped[5] & 0xFF);
                if (jfifLen >= 2 && 2 + jfifLen <= cropped.length) {
                    out.write(cropped, 2, 2 + jfifLen);
                }
            }
            // The original's FULL EXIF APP1, verbatim: MakerNote (0x927C),
            // the 0xA2xx/0xA3xx/0xA4xx vendor tags, UserComment ... that is
            // what the gallery reads to decide which watermarks it may add.
            // Only the dimension tags are retargeted and the IFD1 thumbnail
            // pointer is zeroed, so the block matches the cropped frame.
            // It is written FIRST among the APP1s, exactly like a camera-native
            // file, so a naive "first APP1 is EXIF" reader still lands on it
            // instead of tripping over the XMP header.
            // v68: the whole-block EXIF transplant is back. The white-list
            // path really did drop lens data, exposure bias (EV) and other
            // vendor fields — the parameter watermark does read them from
            // EXIF, so the byte-for-byte copy of the original APP1 returns.
            boolean exifOk = copyPatchedExif(original, out, nw, nh);
            // v66: keep Ultra HDR alive. The gain map is cropped to the same
            // region and appended, then the MPF / XMP declarations are patched
            // so they match the re-assembled container. When the gain map
            // cannot be cropped, the MPF and its XMP are skipped instead and
            // the result is a plain single-image JPEG (no band, no HDR).
            // v70: rolled the whole gain-map experiment back. This is the
            // pre-grey-veil behaviour: the full EXIF transplant (the parameter
            // fix) plus the original's APPn copied verbatim, with no MPF or
            // gain-map surgery at all. Whatever the camera writes after this
            // stays untouched.
            copyForeignMetadata(original, out);
            if (exifOk) {
                int q = copySegments(cropped, out, false);
                out.write(cropped, q, cropped.length - q);
                byte[] result = out.toByteArray();
                if (result.length > 2 && isReadableJpeg(result)) {
                    return result;
                }
                log("spliceJpeg output unreadable, keeping cropped");
            } else {
                log("spliceJpeg: original has no EXIF APP1");
            }
        } catch (Throwable t) {
            log("spliceJpeg error: " + t);
        }
        return cropped;
    }

    /**
     * Copy the original's APP1 EXIF block byte-for-byte, with
     * IFD0 ImageWidth/ImageLength and ExifIFD PixelXDimension/PixelYDimension
     * rewritten to the cropped size and the IFD1 (thumbnail) pointer cleared.
     */
    private static boolean copyPatchedExif(byte[] src,
                                           java.io.ByteArrayOutputStream out,
                                           int nw, int nh) {
        int p = 2;
        while (p + 4 <= src.length) {
            if ((src[p] & 0xFF) != 0xFF) {
                return false;
            }
            int marker = src[p + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return false;
            }
            int len = ((src[p + 2] & 0xFF) << 8) | (src[p + 3] & 0xFF);
            if (len < 2 || p + 2 + len > src.length) {
                return false;
            }
            if (isExifApp1(src, p, marker, len)) {
                byte[] seg = new byte[2 + len];
                System.arraycopy(src, p, seg, 0, seg.length);
                patchTiffDims(seg, 10, nw, nh);
                out.write(seg, 0, seg.length);
                return true;
            }
            p += 2 + len;
        }
        return false;
    }

    private static void patchTiffDims(byte[] b, int t, int nw, int nh) {
        try {
            if (t + 8 > b.length) {
                return;
            }
            boolean le = b[t] == 'I' && b[t + 1] == 'I';
            int off = rd32(b, t + 4, le);
            patchIfd(b, t, t + off, le, nw, nh, true);
        } catch (Throwable ignored) {
        }
    }

    private static void patchIfd(byte[] b, int base, int ifd, boolean le,
                                 int nw, int nh, boolean top) {
        if (ifd < 0 || ifd + 2 > b.length) {
            return;
        }
        int n = rd16(b, ifd, le);
        for (int i = 0; i < n; i++) {
            int e = ifd + 2 + i * 12;
            if (e + 12 > b.length) {
                return;
            }
            int tid = rd16(b, e, le);
            int typ = rd16(b, e + 2, le);
            if (tid == 0x0100 || tid == 0x0101 || tid == 0xA002 || tid == 0xA003) {
                int v = (tid == 0x0100 || tid == 0xA002) ? nw : nh;
                if (typ == 3) {
                    wr16(b, e + 8, v, le);
                } else if (typ == 4) {
                    wr32(b, e + 8, v, le);
                }
            } else if (tid == 0x0112 && typ == 3) {
                // Cropped pixels are already stored in display orientation,
                // so the transplanted rotation tag has to be neutralised.
                wr16(b, e + 8, 1, le);
            } else if (tid == 0x8769 && typ == 4) {
                patchIfd(b, base, base + rd32(b, e + 8, le), le, nw, nh, false);
            }
        }
        if (top) {
            int next = ifd + 2 + n * 12;
            if (next + 4 <= b.length) {
                wr32(b, next, 0, le);
            }
        }
    }

    private static int rd16(byte[] b, int o, boolean le) {
        return le ? ((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8))
                : (((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF));
    }

    private static int rd32(byte[] b, int o, boolean le) {
        if (le) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                    | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
        }
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void wr16(byte[] b, int o, int v, boolean le) {
        if (le) {
            b[o] = (byte) (v & 0xFF);
            b[o + 1] = (byte) ((v >> 8) & 0xFF);
        } else {
            b[o] = (byte) ((v >> 8) & 0xFF);
            b[o + 1] = (byte) (v & 0xFF);
        }
    }

    private static void wr32(byte[] b, int o, int v, boolean le) {
        if (le) {
            b[o] = (byte) (v & 0xFF);
            b[o + 1] = (byte) ((v >> 8) & 0xFF);
            b[o + 2] = (byte) ((v >> 16) & 0xFF);
            b[o + 3] = (byte) ((v >> 24) & 0xFF);
        } else {
            b[o] = (byte) ((v >> 24) & 0xFF);
            b[o + 1] = (byte) ((v >> 16) & 0xFF);
            b[o + 2] = (byte) ((v >> 8) & 0xFF);
            b[o + 3] = (byte) (v & 0xFF);
        }
    }

    /** Cheap structural sanity check so we never ship an unreadable JPEG. */
    private static boolean isReadableJpeg(byte[] data) {
        android.graphics.BitmapFactory.Options o =
                new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    /** Copy the original's APPn/COM blocks except its EXIF APP1. */
    private static void copyForeignMetadata(byte[] src,
                                            java.io.ByteArrayOutputStream out) {
        int p = 2;
        StringBuilder kept = new StringBuilder();
        while (p + 4 <= src.length) {
            if ((src[p] & 0xFF) != 0xFF) {
                break;
            }
            int marker = src[p + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int len = ((src[p + 2] & 0xFF) << 8) | (src[p + 3] & 0xFF);
            if (len < 2 || p + 2 + len > src.length) {
                break;
            }
            boolean metadata = (marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE;
            if (metadata && !isExifApp1(src, p, marker, len)) {
                out.write(src, p, 2 + len);
                kept.append(Integer.toHexString(marker)).append('/')
                        .append(len).append(' ');
            }
            p += 2 + len;
        }
        if (kept.length() > 0) {
            log("foreign meta kept: " + kept);
        }
    }

    private static boolean isExifApp1(byte[] src, int p, int marker, int len) {
        if (marker != 0xE1 || len < 8) {
            return false;
        }
        int q = p + 4;
        return src[q] == 'E' && src[q + 1] == 'x' && src[q + 2] == 'i'
                && src[q + 3] == 'f' && src[q + 4] == 0;
    }

    /** APP2 whose payload starts with "MPF\0" (multi-picture / gain-map). */
    private static boolean isMpf(byte[] src, int p, int marker, int len) {
        if (marker != 0xE2 || len < 8) {
            return false;
        }
        int q = p + 4;
        return src[q] == 'M' && src[q + 1] == 'P' && src[q + 2] == 'F'
                && src[q + 3] == 0;
    }

    /**
     * APP1 XMP that advertises a Google Container "GainMap" item. The wide
     * original carries one even though the spliced payload is a single scan;
     * left in place the editor keeps looking for a gain map that is not there.
     */
    private static boolean isGainMapXmp(byte[] src, int p, int marker, int len) {
        if (marker != 0xE1 || len < 29) {
            return false;
        }
        int q = p + 4;
        if (q + 4 > src.length) {
            return false;
        }
        // Only the XMP namespace is a candidate, never the EXIF APP1.
        if (!(src[q] == 'h' && src[q + 1] == 't' && src[q + 2] == 't'
                && src[q + 3] == 'p')) {
            return false;
        }
        int end = p + 2 + len;
        if (end > src.length) {
            end = src.length;
        }
        for (int i = q; i + 7 <= end; i++) {
            if (src[i] == 'G' && src[i + 1] == 'a' && src[i + 2] == 'i'
                    && src[i + 3] == 'n' && src[i + 4] == 'M'
                    && src[i + 5] == 'a' && src[i + 6] == 'p') {
                return true;
            }
        }
        return false;
    }

    private static boolean matchAt(byte[] b, int off, String s) {
        if (off < 0 || off + s.length() > b.length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (b[off + i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walk the JPEG's leading segments. wantMetadata=true copies APPn/COM
     * blocks (EXIF, XMP, MakerNote, private data); false copies the frame
     * blocks (DQT/SOF/DHT) instead. Returns the offset where it stopped.
     */
    private static int copySegments(byte[] src, java.io.ByteArrayOutputStream out,
                                    boolean wantMetadata) {
        int p = 2;
        while (p + 4 <= src.length) {
            if ((src[p] & 0xFF) != 0xFF) {
                break;
            }
            int marker = src[p + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int len = ((src[p + 2] & 0xFF) << 8) | (src[p + 3] & 0xFF);
            if (len < 2 || p + 2 + len > src.length) {
                break;
            }
            boolean metadata = (marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE;
            if (metadata == wantMetadata) {
                out.write(src, p, 2 + len);
            }
            p += 2 + len;
        }
        return p;
    }

    private static String fosPath(Object fos) {
        if (fos == null) return "null";
        Class<?> c = fos.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("path");
                f.setAccessible(true);
                Object v = f.get(fos);
                return String.valueOf(v);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return "?";
            }
        }
        return "?";
    }

    /**
     * Last-mile net: whatever native encoder produced the final JPEG, it has to
     * land on disk through a FileOutputStream. Catch the write, crop, swap, and
     * log the target path so we can tell temp writes from the real file.
     */
    private void hookFileOutputStreamWrite(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!mWideActive) {
                            return;
                        }
                        String path = fosPath(param.thisObject);
                        for (int i = 0; i < param.args.length; i++) {
                            Object a = param.args[i];
                            if (a instanceof byte[]) {
                                byte[] b = (byte[]) a;
                                if (b.length > 300000 && b.length > 2
                                        && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
                                    byte[] nb = cropJpegIfWide(b);
                                    log("fos.write len=" + b.length + " path=" + path
                                            + " cropped=" + (nb != b));
                                    if (nb != b) {
                                        param.args[i] = nb;
                                        if (param.args.length == 3) {
                                            param.args[1] = 0;
                                            param.args[2] = nb.length;
                                        }
                                    }
                                    return;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log("fos hook error: " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!mWideActive) {
                            return;
                        }
                        String path = fosPath(param.thisObject);
                        if (path == null || !path.endsWith(".jpg")) {
                            return;
                        }
                        java.io.File f = new java.io.File(path);
                        if (!f.exists()) {
                            return;
                        }
                        long fl = f.length();
                        if (fl < 500000 || fl > 4000000) {
                            return;
                        }
                        alignHdrGainDeclarations(f);
                    } catch (Throwable t) {
                        log("fos after hook error: " + t);
                    }
                }

                /**
                 * v71: the camera writes the main frame and the Ultra-HDR gain
                 * map in two passes. The main frame still carries the ORIGINAL
                 * MPF / XMP declarations, so the gain-map offset / length point
                 * at the uncropped file and the declared length is short
                 * (154298 vs the real 192600). The gallery then reads only ~80%
                 * of the gain-map entropy and the lower part of the frame never
                 * brightens - the "grey veil". Once the gain map has landed we
                 * rewrite just the three 32-bit MPF fields and the decimal XMP
                 * Item:Length so both declarations match the real payload.
                 */
                private void alignHdrGainDeclarations(java.io.File f) {
                    try {
                        byte[] b = readAllBytes(f);
                        int n = b.length;
                        if (n < 1000 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
                            return;
                        }
                        if (!((b[n - 2] & 0xFF) == 0xFF && (b[n - 1] & 0xFF) == 0xD9)) {
                            return;
                        }
                        int gainSOI = -1;
                        for (int i = n - 3; i >= 4; i--) {
                            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xD8
                                    && (b[i + 2] & 0xFF) == 0xFF) {
                                gainSOI = i;
                                break;
                            }
                        }
                        if (gainSOI < 4) {
                            return;
                        }
                        if (!((b[gainSOI - 2] & 0xFF) == 0xFF
                                && (b[gainSOI - 1] & 0xFF) == 0xD9)) {
                            return;
                        }
                        int p = 2;
                        int mpfPos = -1;
                        int xmpPos = -1;
                        while (p + 4 <= gainSOI) {
                            if ((b[p] & 0xFF) != 0xFF) {
                                break;
                            }
                            int marker = b[p + 1] & 0xFF;
                            if (marker == 0xDA || marker == 0xD9) {
                                break;
                            }
                            int len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                            if (len < 2 || p + 2 + len > n) {
                                break;
                            }
                            if (marker == 0xE2 && isMpf(b, p, marker, len)) {
                                mpfPos = p;
                            } else if (marker == 0xE1
                                    && isGainMapXmp(b, p, marker, len)) {
                                xmpPos = p;
                            }
                            p += 2 + len;
                        }
                        if (mpfPos < 0) {
                            return;
                        }
                        int t = mpfPos + 8;
                        if (t + 8 > n) {
                            return;
                        }
                        boolean le = !(b[t] == 'M' && b[t + 1] == 'M');
                        int ifd = t + rd32(b, t + 4, le);
                        if (ifd + 2 > n) {
                            return;
                        }
                        int cnt = rd16(b, ifd, le);
                        int voff = -1;
                        for (int i = 0; i < cnt; i++) {
                            int e = ifd + 2 + i * 12;
                            if (e + 12 > n) {
                                break;
                            }
                            if (rd16(b, e, le) == 0xB002) {
                                int c = rd32(b, e + 4, le);
                                voff = (c * 4 <= 4) ? e + 8
                                        : t + rd32(b, e + 8, le);
                                break;
                            }
                        }
                        if (voff < 0 || voff + 32 > n) {
                            return;
                        }
                        int mainLen = gainSOI - t;
                        int gainLen = n - gainSOI;
                        if (mainLen <= 0 || gainLen <= 0) {
                            return;
                        }
                        boolean need = rd32(b, voff + 4, le) != mainLen
                                || rd32(b, voff + 16 + 8, le) != mainLen
                                || rd32(b, voff + 16 + 4, le) != gainLen;
                        int xmpDs = -1;
                        int xmpDe = -1;
                        if (xmpPos > 0) {
                            int xend = xmpPos + 2 + ((b[xmpPos + 2] & 0xFF) << 8)
                                    + (b[xmpPos + 3] & 0xFF);
                            if (xend > n) {
                                xend = n;
                            }
                            String key = "Item:Length=\"";
                            for (int i = xmpPos + 4; i + key.length() + 1 < xend; i++) {
                                if (!matchAt(b, i, key)) {
                                    continue;
                                }
                                int ds = i + key.length();
                                int de = ds;
                                while (de < xend && b[de] >= '0' && b[de] <= '9') {
                                    de++;
                                }
                                if (de > ds) {
                                    xmpDs = ds;
                                    xmpDe = de;
                                }
                                break;
                            }
                            if (xmpDs > 0) {
                                String cur = new String(b, xmpDs, xmpDe - xmpDs);
                                if (!cur.equals(Integer.toString(gainLen))) {
                                    need = true;
                                }
                            }
                        }
                        if (!need) {
                            return;
                        }
                        java.io.RandomAccessFile raf =
                                new java.io.RandomAccessFile(f, "rw");
                        try {
                            raf.seek(voff + 4);
                            raf.write(le32(mainLen, le));
                            raf.seek(voff + 16 + 8);
                            raf.write(le32(mainLen, le));
                            raf.seek(voff + 16 + 4);
                            raf.write(le32(gainLen, le));
                            String nv = Integer.toString(gainLen);
                            if (xmpDs > 0 && (xmpDe - xmpDs) == nv.length()) {
                                raf.seek(xmpDs);
                                raf.write(nv.getBytes("US-ASCII"));
                            }
                        } finally {
                            raf.close();
                        }
                        log("aligned HDR gain: mainLen=" + mainLen + " gainLen="
                                + gainLen + " " + f.getName());
                    } catch (Throwable t) {
                        log("alignHdrGainDeclarations error: " + t);
                    }
                }

                private byte[] le32(int v, boolean le) {
                    byte[] r = new byte[4];
                    if (le) {
                        r[0] = (byte) (v & 0xFF);
                        r[1] = (byte) ((v >> 8) & 0xFF);
                        r[2] = (byte) ((v >> 16) & 0xFF);
                        r[3] = (byte) ((v >> 24) & 0xFF);
                    } else {
                        r[0] = (byte) ((v >> 24) & 0xFF);
                        r[1] = (byte) ((v >> 16) & 0xFF);
                        r[2] = (byte) ((v >> 8) & 0xFF);
                        r[3] = (byte) (v & 0xFF);
                    }
                    return r;
                }
            };
            XposedHelpers.findAndHookMethod("java.io.FileOutputStream", lp.classLoader,
                    "write", byte[].class, hook);
            XposedHelpers.findAndHookMethod("java.io.FileOutputStream", lp.classLoader,
                    "write", byte[].class, int.class, int.class, hook);
            log("hooked FileOutputStream.write (65:24 crop)");
        } catch (Throwable t) {
            log("hook FileOutputStream.write FAILED: " + t);
        }
    }

    /**
     * Some savers grab the channel and write a ByteBuffer directly. Catch that
     * path too, because it completely bypasses FileOutputStream.write(byte[]).
     */
    private void hookFileChannelWrite(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("java.nio.channels.FileChannel", lp.classLoader,
                    "write", java.nio.ByteBuffer.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!mWideActive) return;
                                Object a = param.args[0];
                                if (!(a instanceof java.nio.ByteBuffer)) return;
                                java.nio.ByteBuffer buf = (java.nio.ByteBuffer) a;
                                int rem = buf.remaining();
                                if (rem < 300000) return;
                                java.nio.ByteBuffer dup = buf.duplicate();
                                byte[] b = new byte[rem];
                                dup.get(b);
                                if (!((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8)) return;
                                byte[] nb = cropJpegIfWide(b);
                                if (nb != b) {
                                    param.args[0] = java.nio.ByteBuffer.wrap(nb);
                                    log("FileChannel.write cropped -> " + nb.length);
                                }
                            } catch (Throwable t) {
                                log("fc hook error: " + t);
                            }
                        }
                    });
            log("hooked FileChannel.write (65:24 crop)");
        } catch (Throwable t) {
            log("hook FileChannel.write FAILED: " + t);
        }
    }

    private static volatile Object sAppContext;

    /**
     * The post-processor runs on its own thread, where AndroidAppHelper
     * returned null and killed the whole MediaStore notification chain.
     * Try every available route and cache the first hit.
     */
    private static Object getAppContext() {
        Object cached = sAppContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> c = Class.forName("android.app.AndroidAppHelper");
            java.lang.reflect.Method m = c.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object ctx = m.invoke(null);
            if (ctx != null) {
                sAppContext = ctx;
                return ctx;
            }
        } catch (Throwable ignored) {
        }
        // Plain static that hands back the process Application from any thread.
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object ctx = m.invoke(null);
            if (ctx != null) {
                sAppContext = ctx;
                return ctx;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method sm = at.getDeclaredMethod("systemMain");
            sm.setAccessible(true);
            Object thread = sm.invoke(null);
            java.lang.reflect.Method ga = thread.getClass().getMethod("getApplication");
            ga.setAccessible(true);
            Object ctx = ga.invoke(thread);
            if (ctx != null) {
                sAppContext = ctx;
                return ctx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void cropFileInPlace(java.io.File f) {
        try {
            byte[] data = readAllBytes(f);
            byte[] nb = cropJpegIfWide(data);
            if (nb == data) {
                return;
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            fos.write(nb);
            fos.flush();
            fos.close();
            log("post-crop FILE " + f.getName() + " " + data.length + " -> " + nb.length);
            try {
                Object ctx = getAppContext();
                log("crop-notify ctx=" + (ctx != null) + " file=" + f.getName());
                if (ctx == null) {
                    // Last-ditch fallback: ask the shell media scanner to pick the
                    // file up. Harmless when the app uid cannot broadcast.
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c",
                                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://"
                                        + f.getAbsolutePath()});
                    } catch (Throwable ignored2) {
                    }
                }
                if (ctx != null) {
                    android.content.Context c = (android.content.Context) ctx;
                    android.content.ContentResolver cr = c.getContentResolver();
                    long id = -1;
                    String abs = f.getAbsolutePath();
                    String alt = abs.replace("/sdcard/", "/storage/emulated/0/");
                    if (alt.equals(abs)) {
                        alt = abs.replace("/storage/emulated/0/", "/sdcard/");
                    }
                    String[] candidates = abs.equals(alt)
                            ? new String[]{abs} : new String[]{abs, alt};
                    for (String cand : candidates) {
                        try {
                            android.database.Cursor cur = cr.query(
                                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    new String[]{android.provider.MediaStore.Images.Media._ID},
                                    android.provider.MediaStore.Images.Media.DATA + "=?",
                                    new String[]{cand}, null);
                            if (cur != null) {
                                if (cur.moveToFirst()) id = cur.getLong(0);
                                cur.close();
                            }
                        } catch (Throwable ignored2) {}
                        if (id != -1) break;
                    }
                    if (id == -1) {
                        try {
                            android.database.Cursor cur = cr.query(
                                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    new String[]{android.provider.MediaStore.Images.Media._ID},
                                    android.provider.MediaStore.Images.Media.DISPLAY_NAME + "=?",
                                    new String[]{f.getName()},
                                    android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC");
                            if (cur != null) {
                                if (cur.moveToFirst()) id = cur.getLong(0);
                                cur.close();
                            }
                        } catch (Throwable ignored2) {}
                    }
                    log("crop-notify resolved id=" + id + " for " + f.getName());
                    if (id != -1) {
                        android.net.Uri item = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                        // 1) Toggle IS_PENDING 1 -> 0. This is the documented way to
                        //    tell MediaProvider the pixels changed, so it re-decodes
                        //    the image and throws away the cached thumbnail for this id.
                        try {
                            android.content.ContentValues p = new android.content.ContentValues();
                            p.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                            int u1 = cr.update(item, p, null, null);
                            p.clear();
                            p.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                            int u2 = cr.update(item, p, null, null);
                            log("is_pending toggled id=" + id + " u1=" + u1 + " u2=" + u2);
                        } catch (Throwable t) {
                            log("is_pending toggle failed: " + t);
                        }
                        // 2) Legacy thumbnail table (pre-Q devices). Best effort;
                        //    on newer builds this table is gone and the call throws.
                        try {
                            int d = cr.delete(
                                    android.provider.MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI,
                                    android.provider.MediaStore.Images.Thumbnails.IMAGE_ID + "=?",
                                    new String[]{String.valueOf(id)});
                            log("legacy thumb delete rows=" + d);
                        } catch (Throwable t) {
                            log("legacy thumb delete failed: " + t);
                        }
                        // 3) Bump DATE_MODIFIED and notify observers so anything
                        //    keyed on (path, mtime) is forced to refresh too.
                        try {
                            android.content.ContentValues cv = new android.content.ContentValues();
                            cv.put(android.provider.MediaStore.Images.Media.DATE_MODIFIED,
                                    System.currentTimeMillis() / 1000L);
                            cr.update(item, cv, null, null);
                        } catch (Throwable ignored2) {}
                        try {
                            cr.notifyChange(item, null);
                        } catch (Throwable ignored2) {}
                        log("invalidated thumbnail id=" + id + " for " + f.getName());
                    }
                    android.media.MediaScannerConnection.scanFile(
                            c,
                            new String[]{f.getAbsolutePath()}, null, null);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            log("cropFileInPlace error: " + t);
        }
    }

    // Per-file decision, latched the first time the post-processor sees a shot.
    // The wide flag can flip while the camera re-configures between frames, so
    // the decision must not be re-evaluated on later passes.
    private final java.util.Map<String, Boolean> mPendingCrop =
            new java.util.concurrent.ConcurrentHashMap<>();
    // path -> "mtime:size" of the last version inspected. The camera restores
    // the original mtime when it re-writes the full-resolution frame over the
    // cropped one, so mtime alone never changes between versions. The size does
    // (1.6 MB cropped vs 5.2 MB full), and that is what has to drive detection.
    private final java.util.concurrent.ConcurrentHashMap<String, String> mCropSeen =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Last moment the ratio getter reported "wide". A short grace window keeps
    // a burst from losing frames to a transient non-wide read.
    private volatile long mWideLastSeenAt = 0L;

    /**
     * True when the JPEG on disk already matches the 65:24 target (either
     * orientation). Confirms a crop actually landed instead of trusting the
     * write, so a half-written file gets retried on the next pass.
     */
    /** How long after a capture the post-processor keeps watching a file.
     *  The camera writes the full-resolution frame back within a second or two,
     *  so anything older than this is settled and must be left alone — a manual
     *  edit in the gallery must never be re-cropped by us. */
    private static final long CROP_WINDOW_MS = 60 * 1000L;

    /**
     * True when the JPEG on disk already matches the 65:24 target (either
     * orientation). Confirms a crop actually landed instead of trusting the
     * write, so a half-written file gets retried on the next pass.
     */
    private static boolean fileMatchesTarget(java.io.File f) {
        int[] wh = jpegSize(f);
        if (wh == null || wh[0] < 1500) {
            return false;
        }
        double target = (wh[1] > wh[0]) ? (1.0 / TARGET_RATIO) : TARGET_RATIO;
        double ratio = (double) wh[0] / (double) wh[1];
        return Math.abs(ratio - target) < 0.03;
    }

    /**
     * Width/height of a JPEG, or null when the header cannot be parsed. Only
     * a 256 KB prefix is read first — enough to clear any EXIF APP1 segment,
     * which is capped at 64 KB — and the whole file is pulled in only for the
     * rare frame whose SOF marker sits further in.
     */
    private static int[] jpegSize(java.io.File f) {
        int[] wh = decodeSize(f, 262144);
        if (wh == null) {
            wh = decodeSize(f, Integer.MAX_VALUE);
        }
        return wh;
    }

    private static int[] decodeSize(java.io.File f, int prefix) {
        try {
            byte[] data = (prefix == Integer.MAX_VALUE)
                    ? readAllBytes(f) : readPrefix(f, prefix);
            if (data == null || data.length < 4) {
                return null;
            }
            android.graphics.BitmapFactory.Options o =
                    new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                return null;
            }
            return new int[]{o.outWidth, o.outHeight};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Header-only read, so the post-processor can classify a frame without
     * dragging the whole image through memory on every pass.
     */
    private static byte[] readPrefix(java.io.File f, int max) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            long len = f.length();
            int cap = (int) Math.min((long) max, len);
            byte[] buf = new byte[cap];
            int off = 0;
            while (off < cap) {
                int n = in.read(buf, off, cap - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            if (off == cap) {
                return buf;
            }
            byte[] trimmed = new byte[off];
            System.arraycopy(buf, 0, trimmed, 0, off);
            return trimmed;
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** "WxH" of a JPEG on disk, or "?" when it cannot be decoded. Diagnostics only. */
    private static String jpegDimsOf(java.io.File f) {
        int[] wh = jpegSize(f);
        return (wh == null) ? "?" : (wh[0] + "x" + wh[1]);
    }

    /**
     * Safety net that does not care how the JPEG reached disk: watch the DCIM
     * folders and centre-crop any fresh full-screen JPEG in place. Runs only
     * inside the camera process.
     *
     * The decision to crop is latched per file the first time it is seen and
     * is never re-derived. A shot that lands mid-burst used to be skipped when
     * the ratio getter reported a transient non-wide value on the next pass;
     * once a path is marked, it stays marked until it is confirmed cropped.
     */
    private void startPostProcessor() {
        if (mPostProcStarted) return;
        mPostProcStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String[] dirs = new String[]{"/sdcard/DCIM/Camera", "/sdcard/Pictures"};
                while (true) {
                    long now = System.currentTimeMillis();
                    if (mWideActive) {
                        mWideLastSeenAt = now;
                    }
                    // Fast path only while a wide shot is in flight or has just
                    // landed. The grace window is the same 60 s the camera gets
                    // to rewrite the full-resolution frame over our crop, so
                    // past it there is nothing left to catch. Outside that
                    // window the thread parks without touching the filesystem:
                    // no listFiles, no FUSE round-trips, nothing but a timed
                    // wait that any wide signal can cut short.
                    boolean active = mWideActive
                            || (now - mWideLastSeenAt < WIDE_GRACE_MS)
                            || isWideSelected(sLp);
                    if (!active) {
                        sleepPostProcessor(IDLE_POLL_MS);
                        continue;
                    }
                    sleepPostProcessor(ACTIVE_POLL_MS);
                    now = System.currentTimeMillis();
                    if (mWideActive) {
                        mWideLastSeenAt = now;
                    }
                    for (String d : dirs) {
                        java.io.File dir = new java.io.File(d);
                        java.io.File[] fs = dir.listFiles();
                        if (fs == null) continue;
                        for (java.io.File f : fs) {
                            try {
                                String n = f.getName().toLowerCase();
                                if (!(n.endsWith(".jpg") || n.endsWith(".jpeg"))) continue;
                                // Whitelist: only touch photos produced by OPPO Camera itself
                                // (IMG + 14-digit timestamp). Third-party apps (watermark
                                // cameras, GCam, etc.) drop differently-named files into the
                                // same folders and must NOT be cropped by us.
                                if (!n.matches("img\\d{14}(_\\d+)?\\.(jpg|jpeg)")) continue;
                                long len = f.length();
                                if (len < 300000) continue;
                                long mt = f.lastModified();
                                if (mt < mLoadTime) continue;
                                String path = f.getAbsolutePath();
                                // Only fresh captures. The camera writes the
                                // full-resolution frame back within seconds of
                                // the cropped bytes landing, so anything older
                                // than this window is settled and must be left
                                // alone — a later manual edit in the gallery is
                                // not ours to re-crop.
                                if (now - mt > CROP_WINDOW_MS) {
                                    mPendingCrop.remove(path);
                                    mCropSeen.remove(path);
                                    mLastSize.remove(path);
                                    continue;
                                }
                                Long prev = mLastSize.put(path, Long.valueOf(len));
                                boolean settled = (prev != null && prev.longValue() == len)
                                        || (now - mt > 1500L);
                                if (!settled) continue;
                                Boolean want = mPendingCrop.get(path);
                                if (want == null) {
                                    want = Boolean.valueOf(
                                            mWideActive || (now - mWideLastSeenAt < 2500L)
                                                    || isWideSelected(sLp));
                                    mPendingCrop.put(path, want);
                                    log("post-proc latch " + f.getName() + " len=" + len
                                            + " dims=" + jpegDimsOf(f)
                                            + " crop=" + want + " wide=" + mWideActive);
                                }
                                if (!want.booleanValue()) continue;
                                // Already 65:24: nothing to do, but keep watching.
                                // The camera routinely writes the full-resolution
                                // frame AFTER the cropped bytes land, so a permanent
                                // "done" flag let that overwrite survive untouched.
                                String sig = mt + ":" + len;
                                String seenAt = mCropSeen.get(path);
                                if (sig.equals(seenAt)) {
                                    continue;
                                }
                                mCropSeen.put(path, sig);
                                if (fileMatchesTarget(f)) {
                                    continue;
                                }
                                cropFileInPlace(f);
                                log("post-proc crop " + f.getName() + " mtime=" + mt
                                        + " size=" + len
                                        + " dims=" + jpegDimsOf(f)
                                        + " ok=" + fileMatchesTarget(f));
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }, "WidePostProc");
        t.setDaemon(true);
        t.start();
        log("post-processor started (latched per-file decision + verify/retry)");
    }

    /**
     * OPPO's own saver kc.m1.g(String path, Bitmap, byte[]) - crop the bitmap
     * argument before it reaches the encoder.
     */
    private void hookKcM1G(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("kc.m1", lp.classLoader, "g",
                    String.class, android.graphics.Bitmap.class, byte[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!mWideActive) {
                                    return;
                                }
                                // Burst frames can arrive already JPEG-encoded in the
                                // byte[] arg, before the Bitmap path runs. Crop that
                                // copy first so every frame in a burst is 65:24.
                                Object rawArg = param.args[2];
                                if (rawArg instanceof byte[]) {
                                    byte[] rawBytes = (byte[]) rawArg;
                                    if (rawBytes.length > 300000 && rawBytes.length > 2
                                            && (rawBytes[0] & 0xFF) == 0xFF
                                            && (rawBytes[1] & 0xFF) == 0xD8) {
                                        byte[] nb = cropJpegIfWide(rawBytes);
                                        if (nb != rawBytes) {
                                            param.args[2] = nb;
                                            log("cropped kc.m1 byte[] " + rawBytes.length
                                                    + " -> " + nb.length);
                                            return;
                                        }
                                    }
                                }
                                Object bmp = param.args[1];
                                if (bmp instanceof android.graphics.Bitmap) {
                                    android.graphics.Bitmap src = (android.graphics.Bitmap) bmp;
                                    int sw = src.getWidth();
                                    int sh = src.getHeight();
                                    int[] crop = centredWideCrop(sw, sh);
                                    if (crop != null) {
                                        android.graphics.Bitmap cropped =
                                                android.graphics.Bitmap.createBitmap(
                                                        src, crop[0], crop[1], crop[2], crop[3]);
                                        param.args[1] = cropped;
                                        log("cropped kc.m1 bitmap " + sw + "x" + sh
                                                + " -> " + crop[2] + "x" + crop[3]);
                                    }
                                }
                            } catch (Throwable t) {
                                log("kc.m1.g hook error: " + t);
                            }
                        }
                    });
            log("hooked kc.m1.g (65:24 crop)");
        } catch (Throwable t) {
            log("hook kc.m1.g FAILED: " + t);
        }
    }

    /**
     * Post-capture 65:24 crop. The device HAL exposes no native 65:24 output
     * and the XPAN preview-key injection was ignored, so the only reliable
     * path left is to crop the final JPEG right before it is encoded. We hook
     * Bitmap.compress inside the camera process and, while "wide" is selected,
     * swap the source bitmap for a centre-cropped 65:24 one.
     */
    private void hookBitmapCompress(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.graphics.Bitmap", lp.classLoader,
                    "compress", android.graphics.Bitmap.CompressFormat.class, int.class,
                    java.io.OutputStream.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!mWideActive) {
                                    return;
                                }
                                Object fmt = param.args[0];
                                if (!(fmt instanceof android.graphics.Bitmap.CompressFormat)) {
                                    return;
                                }
                                if (((android.graphics.Bitmap.CompressFormat) fmt)
                                        != android.graphics.Bitmap.CompressFormat.JPEG) {
                                    return;
                                }
                                Object self = param.thisObject;
                                if (!(self instanceof android.graphics.Bitmap)) {
                                    return;
                                }
                                android.graphics.Bitmap src = (android.graphics.Bitmap) self;
                                int w = src.getWidth();
                                int h = src.getHeight();
                                int[] crop = centredWideCrop(w, h);
                                if (crop == null) {
                                    return;
                                }
                                android.graphics.Bitmap cropped =
                                        android.graphics.Bitmap.createBitmap(
                                                src, crop[0], crop[1], crop[2], crop[3]);
                                param.thisObject = cropped;
                                log("cropped " + w + "x" + h + " -> " + crop[2] + "x"
                                        + crop[3] + " (65:24)");
                            } catch (Throwable t) {
                                log("crop hook error: " + t);
                            }
                        }
                    });
            log("hooked Bitmap.compress (65:24 crop)");
        } catch (Throwable t) {
            log("hook Bitmap.compress FAILED: " + t);
        }
    }

    private boolean isWideSelected(XC_LoadPackage.LoadPackageParam lp) {
        if (mWideActive) {
            return true;
        }
        try {
            if (mRatioDataKey == null) {
                return false;
            }
            Class<?> dmClass = lp.classLoader.loadClass("com.oplus.camera.data.DataManager");
            java.lang.reflect.Method gm = dmClass.getDeclaredMethod("getInstance");
            gm.setAccessible(true);
            Object dm = gm.invoke(null);
            Object raw = XposedHelpers.callMethod(dm, "c", mRatioDataKey);
            return "wide".equals(raw);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readKeyName(Object dataKey) {
        if (dataKey == null) {
            return null;
        }
        Class<?> c = dataKey.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("f");
                f.setAccessible(true);
                Object name = f.get(dataKey);
                return (name instanceof String) ? (String) name : null;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean contains(String[] arr, String needle) {
        if (arr == null) return false;
        for (String s : arr) {
            if (needle.equals(s)) return true;
        }
        return false;
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
        // Durable file log: logcat gets flooded by other processes before we can
        // read it back. Append to a file the camera process can definitely write.
        try {
            java.io.File f = new java.io.File(
                    "/sdcard/Android/data/com.oplus.camera/files/WideCamera.log");
            java.io.File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
