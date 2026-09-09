package com.mokano.mokahome;

import android.content.Context;
import android.media.AudioManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Audio output persistence for Allwinner TV boxes. */
public final class AudioController {
    public static final String AUTO = "AUTO";
    public static final String CODEC = "CODEC";
    public static final String HDMI = "HDMI";
    public static final String SPDIF = "SPDIF";

    private static final String KEY_OUTPUT = "audio_output_v6";
    private static final String KEY_RESTORE = "audio_restore_v6";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicLong LAST_APPLY = new AtomicLong(0);

    private AudioController() {}

    public static String savedOutput(Context c) {
        return MokaHomeApp.prefs(c).getString(KEY_OUTPUT, CODEC);
    }

    public static void saveOutput(Context c, String output) {
        if (!isValid(output)) output = AUTO;
        MokaHomeApp.prefs(c).edit().putString(KEY_OUTPUT, output).commit();
    }

    public static boolean isRestoreEnabled(Context c) {
        return MokaHomeApp.prefs(c).getBoolean(KEY_RESTORE, true);
    }

    public static void setRestoreEnabled(Context c, boolean enabled) {
        MokaHomeApp.prefs(c).edit().putBoolean(KEY_RESTORE, enabled).commit();
    }

    public static String label(String output) {
        if (CODEC.equals(output)) return "CODEC";
        if (HDMI.equals(output)) return "HDMI";
        if (SPDIF.equals(output)) return "SPDIF";
        return "Automatico";
    }

    public static void applyPersisted(Context c, boolean force) {
        if (c == null) return;
        String output = savedOutput(c);
        if (AUTO.equals(output)) return;
        if (!force && !isRestoreEnabled(c)) return;
        long now = System.currentTimeMillis();
        long last = LAST_APPLY.get();
        if (!force && now - last < 1200) return;
        LAST_APPLY.set(now);
        Context app = c.getApplicationContext();
        EXEC.execute(() -> applyOutput(app, output));
    }

    public static void restoreAfterBoot(Context c) {
        if (c == null || !isRestoreEnabled(c)) return;
        String output = savedOutput(c);
        if (AUTO.equals(output)) return;
        Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            try { Thread.sleep(3500); } catch (InterruptedException ignored) {}
            applyOutput(app, output);
            try { Thread.sleep(6500); } catch (InterruptedException ignored) {}
            applyOutput(app, output);
        });
    }

    /**
     * Tries the proprietary Allwinner AudioManager API first, then the legacy
     * Allwinner routing property. Multiple paths are intentional because H6 ROMs
     * differ between vendors.
     */
    public static boolean applyOutput(Context c, String output) {
        if (c == null || AUTO.equals(output) || !isValid(output)) return AUTO.equals(output);
        boolean api = false;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                api |= invokeSetAudioDeviceActive(am, output);
                api |= invokeSetParameters(am, "audio_devices_out_active=" + allwinnerName(output));
            }
        } catch (Throwable ignored) {}

        int route = routeValue(output);
        boolean root = MokaHomeApp.runRootScript(
                "setprop audio.routing " + route + "\n" +
                "setprop persist.mokahome.audio_output " + output);
        return api || root;
    }

    private static boolean invokeSetParameters(AudioManager am, String parameter) {
        try {
            Method target = null;
            Class<?> cls = am.getClass();
            for (Method m : cls.getMethods()) {
                if ("setParameters".equals(m.getName()) && m.getParameterTypes().length == 1 &&
                        m.getParameterTypes()[0] == String.class) { target = m; break; }
            }
            if (target == null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if ("setParameters".equals(m.getName()) && m.getParameterTypes().length == 1 &&
                            m.getParameterTypes()[0] == String.class) { target = m; break; }
                }
            }
            if (target == null) return false;
            target.setAccessible(true);
            target.invoke(am, parameter);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean invokeSetAudioDeviceActive(AudioManager am, String output) {
        try {
            String device = hiddenString("AUDIO_NAME_" + output, allwinnerName(output));
            int active = hiddenInt("AUDIO_OUTPUT_ACTIVE", 1);
            ArrayList<String> devices = new ArrayList<>();
            devices.add(device);

            Method target = null;
            for (Method m : am.getClass().getMethods()) {
                if ("setAudioDeviceActive".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    target = m; break;
                }
            }
            if (target == null) {
                for (Method m : am.getClass().getDeclaredMethods()) {
                    if ("setAudioDeviceActive".equals(m.getName()) && m.getParameterTypes().length == 2) {
                        target = m; break;
                    }
                }
            }
            if (target == null) return false;
            target.setAccessible(true);
            target.invoke(am, devices, active);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String hiddenString(String fieldName, String fallback) {
        try {
            Field f = AudioManager.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof String) return (String) value;
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static int hiddenInt(String fieldName, int fallback) {
        try {
            Field f = AudioManager.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static String allwinnerName(String output) {
        if (CODEC.equals(output)) return "AUDIO_CODEC";
        if (HDMI.equals(output)) return "AUDIO_HDMI";
        if (SPDIF.equals(output)) return "AUDIO_SPDIF";
        return "AUDIO_CODEC";
    }

    private static int routeValue(String output) {
        if (HDMI.equals(output)) return 1024;
        if (SPDIF.equals(output)) return 4096;
        return 2; // CODEC / analog output on legacy Allwinner routing.
    }

    private static boolean isValid(String output) {
        return AUTO.equals(output) || CODEC.equals(output) || HDMI.equals(output) || SPDIF.equals(output);
    }
}
