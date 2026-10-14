package app.immersivekeyboard.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Makes an imported custom theme follow the system's dark mode. Gboard's own "follow system"
 * option only covers its built-in themes, so when the system switches, this selects the
 * imported theme with the same name and the opposite light or dark variant, for example
 * "Frosted Keys Dark" and "Frosted Keys Light".
 */
@SuppressWarnings("unused")
public final class ThemeDarkMode {
    private static final String TAG = "ImmersiveKB";
    private static final String THEME_PREFERENCE = "additional_keyboard_theme";
    private static final String FILE_THEME_PREFIX = "files:";
    private static final String USER_THEME_PREFIX = "user_theme_";
    private static final long CHECK_INTERVAL_MS = 1500;

    private static final Map<String, ThemeInfo> CACHE = new HashMap<>();
    private static long lastCheck;
    private static int lastNightMode = -1;

    private ThemeDarkMode() {
    }

    /** Injection point. Called from InputView.dispatchDraw. */
    public static void check(View inputView) {
        try {
            Context context = inputView.getContext();
            int nightMode = context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            long now = SystemClock.uptimeMillis();
            if (nightMode == lastNightMode && now - lastCheck < CHECK_INTERVAL_MS) {
                return;
            }
            lastNightMode = nightMode;
            lastCheck = now;
            boolean wantLight = nightMode != Configuration.UI_MODE_NIGHT_YES;
            apply(context, wantLight);
        } catch (Throwable failure) {
            Log.w(TAG, "theme dark mode check failed", failure);
        }
    }

    private static void apply(Context context, boolean wantLight) {
        Context storage = context.createDeviceProtectedStorageContext();
        SharedPreferences preferences = storage.getSharedPreferences(
                context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        String selected = preferences.getString(THEME_PREFERENCE, "");
        if (selected == null || !selected.startsWith(FILE_THEME_PREFIX + USER_THEME_PREFIX)) {
            return;
        }
        File[] files = context.getFilesDir().listFiles(
                (directory, name) -> name.startsWith(USER_THEME_PREFIX) && name.endsWith(".zip"));
        if (files == null) {
            return;
        }
        String selectedName = selected.substring(FILE_THEME_PREFIX.length());
        ThemeInfo current = null;
        for (File file : files) {
            if (file.getName().equals(selectedName)) {
                current = info(file);
            }
        }
        if (current == null || current.light == wantLight || current.pairName.isEmpty()) {
            return;
        }
        for (File file : files) {
            ThemeInfo candidate = info(file);
            if (candidate != null && candidate.light == wantLight
                    && candidate.pairName.equals(current.pairName)) {
                preferences.edit()
                        .putString(THEME_PREFERENCE, FILE_THEME_PREFIX + file.getName())
                        .apply();
                Log.i(TAG, "theme switched to " + candidate.name);
                return;
            }
        }
    }

    private static ThemeInfo info(File file) {
        String key = file.getName() + ":" + file.lastModified() + ":" + file.length();
        ThemeInfo cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("metadata.json");
            if (entry == null || entry.getSize() > 1024 * 1024) {
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0 && bytes.size() < 1024 * 1024) {
                    bytes.write(buffer, 0, read);
                }
            }
            JSONObject metadata = new JSONObject(bytes.toString("UTF-8"));
            String name = metadata.optString("name", "");
            ThemeInfo info = new ThemeInfo(name, metadata.optBoolean("is_light_theme", false));
            CACHE.put(key, info);
            return info;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static final class ThemeInfo {
        final String name;
        final boolean light;
        /** The name without its light or dark word, shared by both variants. */
        final String pairName;

        ThemeInfo(String name, boolean light) {
            this.name = name;
            this.light = light;
            this.pairName = name.replaceAll("(?i)[\\s_-]*(dark|light|night|day)\\b", "")
                    .trim().toLowerCase(Locale.ROOT);
        }
    }
}
