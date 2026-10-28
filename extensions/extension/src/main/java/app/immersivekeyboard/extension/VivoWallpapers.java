package app.immersivekeyboard.extension;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The wallpapers that come with vivo's Themes app, offered as light and dark mode wallpapers.
 * They ship as plain images inside vivo's theme resource package, which is readable by any
 * app, so they are read straight from it.
 */
final class VivoWallpapers {
    private static final String TAG = "ImmersiveKB";
    private static final String PACKAGE = "com.bbk.theme.resources";
    private static final String FALLBACK_PATH = "/system/app/VivoThemeRes/VivoThemeRes.apk";
    private static final Pattern IMAGE =
            Pattern.compile("res/drawable[^/]*/wallpaper_(\\d+)_cropflag\\d+\\.(png|jpg|webp)");

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2);

    private VivoWallpapers() {
    }

    /** Whether this phone has vivo's wallpapers. */
    static boolean available(Context context) {
        return apk(context) != null;
    }

    private static File apk(Context context) {
        try {
            File file = new File(context.getPackageManager()
                    .getApplicationInfo(PACKAGE, 0).sourceDir);
            if (file.canRead()) {
                return file;
            }
        } catch (Throwable ignored) {
            // Not visible to this app; try where vivo puts it.
        }
        File fallback = new File(FALLBACK_PATH);
        return fallback.canRead() ? fallback : null;
    }

    /** Shows the wallpapers in a grid; the one tapped becomes the mode's wallpaper. */
    static void choose(Context activity, boolean light, Runnable done) {
        File apk = apk(activity);
        if (apk == null) {
            return;
        }
        List<String[]> wallpapers = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                Matcher matcher = IMAGE.matcher(name);
                if (matcher.matches()) {
                    String base = name.substring(0, name.lastIndexOf('.'));
                    String preview = null;
                    for (String extension : new String[]{".png", ".jpg", ".webp"}) {
                        if (zip.getEntry(base + "_small" + extension) != null) {
                            preview = base + "_small" + extension;
                        }
                    }
                    wallpapers.add(new String[]{name, preview != null ? preview : name,
                            matcher.group(1)});
                }
            }
        } catch (Throwable failure) {
            Log.w(TAG, "could not read vivo wallpapers", failure);
            return;
        }
        wallpapers.sort((a, b) -> Integer.compare(Integer.parseInt(a[2]),
                Integer.parseInt(b[2])));
        if (wallpapers.isEmpty()) {
            return;
        }

        float density = activity.getResources().getDisplayMetrics().density;
        int gap = Math.round(8 * density);
        GridView grid = new GridView(activity);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(gap * 2, gap, gap * 2, gap);
        grid.setClipToPadding(false);
        int width = (activity.getResources().getDisplayMetrics().widthPixels * 7 / 10) / 3;
        int height = Math.round(width * 19f / 9f);
        grid.setAdapter(new Previews(apk, wallpapers, width, height, Math.round(6 * density)));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(light ? "vivo wallpaper for light mode" : "vivo wallpaper for dark mode")
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            String entry = wallpapers.get(position)[0];
            dialog.dismiss();
            WallpaperSwitch.save(activity, () -> new EntryStream(apk, entry), light, done);
        });
        dialog.show();
    }

    /** Thumbnails, decoded off the main thread as they scroll into view. */
    private static final class Previews extends BaseAdapter {
        private final File apk;
        private final List<String[]> wallpapers;
        private final int width;
        private final int height;
        private final int corner;
        private final Map<Integer, Bitmap> cache = new ConcurrentHashMap<>();
        private final Handler main = new Handler(Looper.getMainLooper());

        Previews(File apk, List<String[]> wallpapers, int width, int height, int corner) {
            this.apk = apk;
            this.wallpapers = wallpapers;
            this.width = width;
            this.height = height;
            this.corner = corner;
        }

        @Override
        public int getCount() {
            return wallpapers.size();
        }

        @Override
        public Object getItem(int position) {
            return wallpapers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView image = convertView instanceof ImageView ? (ImageView) convertView
                    : new ImageView(parent.getContext());
            image.setLayoutParams(new GridView.LayoutParams(width, height));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
                }
            });
            image.setBackgroundColor(0x22888888);
            image.setTag(position);
            Bitmap cached = cache.get(position);
            image.setImageBitmap(cached);
            if (cached == null) {
                String entry = wallpapers.get(position)[1];
                LOADER.execute(() -> {
                    Bitmap bitmap = decode(entry);
                    if (bitmap == null) {
                        return;
                    }
                    cache.put(position, bitmap);
                    main.post(() -> {
                        if (Integer.valueOf(position).equals(image.getTag())) {
                            image.setImageBitmap(bitmap);
                        }
                    });
                });
            }
            return image;
        }

        private Bitmap decode(String entry) {
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry zipEntry = zip.getEntry(entry);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = zip.getInputStream(zipEntry)) {
                    BitmapFactory.decodeStream(input, null, bounds);
                }
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= width
                        && bounds.outHeight / (sample * 2) >= height) {
                    sample *= 2;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                Bitmap decoded;
                try (InputStream input = zip.getInputStream(zipEntry)) {
                    decoded = BitmapFactory.decodeStream(input, null, options);
                }
                if (decoded == null || decoded.getWidth() <= width) {
                    return decoded;
                }
                // Keep the grid's memory small: only what the cell shows.
                Bitmap scaled = Bitmap.createScaledBitmap(decoded, width,
                        Math.max(1, Math.round(decoded.getHeight() * width
                                / (float) decoded.getWidth())), true);
                if (scaled != decoded) {
                    decoded.recycle();
                }
                return scaled;
            } catch (Throwable failure) {
                return null;
            }
        }
    }

    /** One image inside the package, closing the package along with the stream. */
    private static final class EntryStream extends java.io.FilterInputStream {
        private final ZipFile zip;

        EntryStream(File apk, String entry) throws java.io.IOException {
            this(new ZipFile(apk), entry);
        }

        private EntryStream(ZipFile zip, String entry) throws java.io.IOException {
            super(zip.getInputStream(zip.getEntry(entry)));
            this.zip = zip;
        }

        @Override
        public void close() throws java.io.IOException {
            try {
                super.close();
            } finally {
                zip.close();
            }
        }
    }
}
