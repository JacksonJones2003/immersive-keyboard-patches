package app.immersivekeyboard.extension;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Development aid: applies method calls described in system properties to an object, so
 * vendor-specific blur options can be tried over adb without rebuilding.
 *
 * <pre>
 *   adb shell setprop debug.ikb.d0 "setGlassEffect:f80,f22"
 *   adb shell setprop debug.ikb.d1 "setGlassEffectDispersion:f0.36"
 * </pre>
 *
 * Arguments are prefixed with their type: f float, i int, z boolean (1 or 0), x hex int.
 * Properties d0..d9 target blur drawables and v0..v9 target the keyboard panel view.
 */
final class LiveTuning {
    private static final String TAG = "ImmersiveKB";
    private static final int SLOTS = 10;
    private static Method systemPropertiesGet;
    private static boolean unavailable;

    private LiveTuning() {
    }

    /** All non-empty call specs for a prefix, in slot order. Empty when none are set. */
    static List<String> specs(String prefix) {
        List<String> specs = new ArrayList<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            String value = property("debug.ikb." + prefix + slot);
            if (!value.isEmpty()) {
                specs.add(value);
            }
        }
        return specs;
    }

    static String property(String name) {
        if (unavailable) {
            return "";
        }
        try {
            if (systemPropertiesGet == null) {
                systemPropertiesGet = Class.forName("android.os.SystemProperties")
                        .getDeclaredMethod("get", String.class, String.class);
                systemPropertiesGet.setAccessible(true);
            }
            Object value = systemPropertiesGet.invoke(null, name, "");
            return value == null ? "" : value.toString().trim();
        } catch (Throwable failure) {
            unavailable = true;
            Log.w(TAG, "system properties unavailable: " + failure);
            return "";
        }
    }

    /** Applies each spec to the target, logging what happened. */
    static void apply(Object target, List<String> specs) {
        if (target == null) {
            return;
        }
        for (String spec : specs) {
            try {
                int colon = spec.indexOf(':');
                String name = colon < 0 ? spec : spec.substring(0, colon);
                String[] arguments = colon < 0 || colon == spec.length() - 1
                        ? new String[0] : spec.substring(colon + 1).split(",");
                Method method = find(target.getClass(), name, arguments.length);
                if (method == null) {
                    Log.w(TAG, "tuning: no method " + name + "/" + arguments.length + " on "
                            + target.getClass().getName());
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                Object[] values = new Object[arguments.length];
                for (int index = 0; index < arguments.length; index++) {
                    values[index] = convert(arguments[index].trim(), types[index]);
                }
                method.setAccessible(true);
                Object result = method.invoke(target, values);
                Log.i(TAG, "tuning: " + spec + " on " + target.getClass().getSimpleName()
                        + (method.getReturnType() == void.class ? "" : " -> " + result));
            } catch (Throwable failure) {
                Log.w(TAG, "tuning: " + spec + " failed: " + failure
                        + (failure.getCause() == null ? "" : " / " + failure.getCause()));
            }
        }
    }

    private static Method find(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterTypes().length == parameterCount) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Object convert(String text, Class<?> type) {
        char kind = text.isEmpty() ? ' ' : text.charAt(0);
        String body = text.isEmpty() ? "" : text.substring(1);
        double number;
        if (kind == 'x') {
            number = (int) Long.parseLong(body, 16);
        } else if (kind == 'z') {
            number = "1".equals(body) || "true".equals(body) ? 1 : 0;
        } else {
            number = Double.parseDouble(kind == 'f' || kind == 'i' ? body : text);
        }
        if (type == float.class) {
            return (float) number;
        }
        if (type == int.class) {
            return (int) number;
        }
        if (type == boolean.class) {
            return number != 0;
        }
        if (type == long.class) {
            return (long) number;
        }
        if (type == double.class) {
            return number;
        }
        return type == String.class ? body : null;
    }
}
