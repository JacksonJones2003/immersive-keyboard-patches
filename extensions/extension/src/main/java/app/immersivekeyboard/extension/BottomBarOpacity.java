package app.immersivekeyboard.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Makes the strip Gboard paints behind the system navigation bar look like the rest of the
 * keyboard, so a translucent keyboard is not left with a solid or mismatched bottom bar.
 */
@SuppressWarnings("unused")
public final class BottomBarOpacity {
    private static final String BASE_AREA_TAG = ".keyboard-base-area";
    private static final String BODY_AREA_TAG = ".keyboard-body-area";
    private static final int OPAQUE = 255;
    private static final long REFRESH_INTERVAL_MS = 150;

    // Settings written by the Frosted Glass patch from jasonwu1994/Gboard-patches.
    private static final String FROSTED_GLASS_PREFERENCES = "gboard_patches_settings";
    private static final String FROSTED_GLASS_ENABLED = "pref_frosted_glass_enabled";
    private static final String FROSTED_GLASS_TRANSPARENCY_MODE =
            "pref_frosted_glass_transparency_mode";
    private static final String FROSTED_GLASS_CUSTOM_OPACITY = "pref_frosted_glass_custom_opacity";
    private static final String FROSTED_GLASS_BLUR_STRENGTH = "pref_frosted_glass_blur_radius_px";
    private static final int FROSTED_GLASS_DEFAULT_OPACITY = 20;
    private static final int FROSTED_GLASS_DEFAULT_BLUR_STRENGTH = 20;
    private static final float FROSTED_GLASS_CORNER_RADIUS_DP = 32.0f;
    /** Size of the area inspected at each bottom corner of the keyboard panel. */
    private static final float CORNER_AREA_DP = 48.0f;

    private static final Map<View, State> STATES = new WeakHashMap<>();

    private BottomBarOpacity() {
    }

    /**
     * Injection point. Called from InputView.onDraw with the color of the bottom strip, just
     * before the strip is painted.
     */
    public static int stripColor(View inputView, Canvas canvas, int color) {
        try {
            State state = stateFor(inputView);
            state.watch(inputView);
            int keyboardColor = state.keyboardColor(inputView, color);
            state.drawnColor = keyboardColor;
            state.drawnCornerRadius = state.cornerRadiusPx;
            if (Color.alpha(keyboardColor) >= OPAQUE) {
                return color;
            }
            state.drawBlur(inputView, canvas);
            if (state.drawSeamless(inputView, canvas, keyboardColor)) {
                // The strip was painted here; let Gboard's own rectangle paint nothing.
                return Color.TRANSPARENT;
            }
            return keyboardColor;
        } catch (Throwable ignored) {
            return color;
        }
    }

    /**
     * Injection point. Called before Gboard sets the window's navigation bar color, which is
     * what colors the bottom strip on Android versions where Gboard does not paint it itself.
     */
    public static int navigationBarColor(Window window, int color) {
        try {
            FrostedGlass settings = FrostedGlass.read(window.getContext());
            if (settings.customAlpha < 0 || settings.customAlpha >= OPAQUE
                    || Color.alpha(color) != OPAQUE) {
                return color;
            }
            return withAlpha(color, settings.customAlpha);
        } catch (Throwable ignored) {
            return color;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static State stateFor(View inputView) {
        State state = STATES.get(inputView);
        if (state == null) {
            state = new State();
            STATES.put(inputView, state);
        }
        return state;
    }

    /** The Frosted Glass settings that matter for the strip. */
    private static final class FrostedGlass {
        static final FrostedGlass OFF = new FrostedGlass(false, -1, 0);

        final boolean enabled;
        /** Alpha Frosted Glass applies to keyboard surfaces in custom mode, otherwise -1. */
        final int customAlpha;
        final int blurRadiusPx;

        private FrostedGlass(boolean enabled, int customAlpha, int blurRadiusPx) {
            this.enabled = enabled;
            this.customAlpha = customAlpha;
            this.blurRadiusPx = blurRadiusPx;
        }

        static FrostedGlass read(Context context) {
            try {
                Context application = context.getApplicationContext();
                SharedPreferences preferences = (application == null ? context : application)
                        .getSharedPreferences(FROSTED_GLASS_PREFERENCES, Context.MODE_PRIVATE);
                Map<String, ?> values = preferences.getAll();
                if (!"true".equalsIgnoreCase(String.valueOf(values.get(FROSTED_GLASS_ENABLED)))) {
                    return OFF;
                }
                int customAlpha = -1;
                if ("custom".equals(values.get(FROSTED_GLASS_TRANSPARENCY_MODE))) {
                    int transparency = clamp(number(values.get(FROSTED_GLASS_CUSTOM_OPACITY),
                            FROSTED_GLASS_DEFAULT_OPACITY), 0, 100);
                    customAlpha = Math.round((100 - transparency) * 255f / 100f);
                }
                // Same strength-to-radius mapping Frosted Glass uses: 1..100 -> 1..160 px.
                int strength = clamp(number(values.get(FROSTED_GLASS_BLUR_STRENGTH),
                        FROSTED_GLASS_DEFAULT_BLUR_STRENGTH), 1, 100);
                int radius = Math.round(1 + (strength - 1) * (159f / 99f));
                return new FrostedGlass(true, customAlpha, radius);
            } catch (Throwable ignored) {
                return OFF;
            }
        }

        private static int number(Object raw, int fallback) {
            try {
                return raw instanceof Number
                        ? ((Number) raw).intValue() : Integer.parseInt(String.valueOf(raw));
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class State implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        int drawnColor;
        int drawnCornerRadius;
        /** Radius of the keyboard panel's rounded bottom corners, or 0 when they are square. */
        int cornerRadiusPx;
        private int cornerAreaPx;
        private Bitmap leftCorner;
        private Bitmap rightCorner;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cutOutPaint = new Paint();
        private final Path stripPath = new Path();
        private final int[] origin = new int[2];

        State() {
            // Erases wherever the keyboard paints anything at all, however faintly.
            cutOutPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            cutOutPaint.setColorFilter(new ColorMatrixColorFilter(new float[]{
                    1, 0, 0, 0, 0,
                    0, 1, 0, 0, 0,
                    0, 0, 1, 0, 0,
                    0, 0, 0, 255, 0}));
        }
        private WeakReference<View> inputView = new WeakReference<>(null);
        private WeakReference<View> surface = new WeakReference<>(null);
        private final Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        private final Canvas pixelCanvas = new Canvas(pixel);
        private final int[] location = new int[2];
        private FrostedGlass settings = FrostedGlass.OFF;
        private long lastRefresh = -REFRESH_INTERVAL_MS;
        private int cachedColor;
        private int cachedFallback;
        private boolean watching;
        private boolean attachListenerAdded;
        private Drawable blur;
        private boolean blurUnavailable;

        /**
         * Color and opacity of the keyboard as it appears on screen, or the given color
         * unchanged when the keyboard is opaque or cannot be inspected.
         */
        int keyboardColor(View inputView, int fallback) {
            long now = SystemClock.uptimeMillis();
            if (now - lastRefresh < REFRESH_INTERVAL_MS && fallback == cachedFallback) {
                return cachedColor;
            }
            lastRefresh = now;
            cachedFallback = fallback;
            settings = FrostedGlass.read(inputView.getContext());
            int sampled = sampleKeyboard(inputView);
            boolean frostedCustom = settings.customAlpha >= 0 && settings.customAlpha < OPAQUE;
            if (Color.alpha(sampled) >= OPAQUE && frostedCustom) {
                // Frosted Glass is set to be see-through but the keyboard was read as solid:
                // trust the setting for opacity and keep the keyboard's color.
                cachedColor = withAlpha(sampled, settings.customAlpha);
            } else if (Color.alpha(sampled) > 0) {
                cachedColor = sampled;
            } else if (settings.customAlpha >= 0 && Color.alpha(fallback) == OPAQUE) {
                cachedColor = withAlpha(fallback, settings.customAlpha);
            } else {
                cachedColor = fallback;
            }
            try {
                measureCorners(inputView, cachedColor);
            } catch (Throwable ignored) {
                cornerRadiusPx = 0;
            }
            return cachedColor;
        }

        /**
         * Paints the backgrounds stacked under the keys into a single pixel, in the order the
         * keyboard draws them, and returns the result. Whatever the theme and Frosted Glass
         * did to those backgrounds (color, alpha, layering) is reflected in that pixel.
         */
        private int sampleKeyboard(View inputView) {
            try {
                List<View> stack = keyboardStack(inputView);
                if (stack.isEmpty()) {
                    return 0;
                }
                View target = stack.get(0);
                target.getLocationInWindow(location);
                float sampleX = location[0] + target.getWidth() / 2f;
                float sampleY = location[1] + target.getHeight() / 2f;
                pixel.eraseColor(Color.TRANSPARENT);
                drawStack(pixelCanvas, stack, sampleX, sampleY);
                return pixel.getPixel(0, 0);
            } catch (Throwable ignored) {
                return 0;
            }
        }

        /**
         * The view holding the keyboard's body background followed by its ancestors, up to but
         * not including InputView. Empty when the keyboard cannot be found.
         */
        private List<View> keyboardStack(View inputView) {
            List<View> stack = new ArrayList<>();
            View target = surface.get();
            if (target == null || !target.isAttachedToWindow() || !target.isShown()) {
                View root = inputView.getRootView();
                target = find(root, BODY_AREA_TAG);
                if (target == null) {
                    target = find(root, BASE_AREA_TAG);
                }
                surface = new WeakReference<>(target);
            }
            if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
                return stack;
            }
            for (View view = target; view != null && view != inputView; ) {
                stack.add(view);
                ViewParent parent = view.getParent();
                view = parent instanceof View ? (View) parent : null;
            }
            return stack;
        }

        /**
         * Paints the stack's backgrounds, outermost first, the way the keyboard draws them.
         * The given window coordinates end up at the canvas origin.
         */
        private void drawStack(Canvas canvas, List<View> stack, float windowX, float windowY) {
            int base = canvas.save();
            for (int index = stack.size() - 1; index >= 0; index--) {
                View view = stack.get(index);
                if (view.getVisibility() != View.VISIBLE) {
                    continue;
                }
                view.getLocationInWindow(location);
                float left = location[0] - windowX;
                float top = location[1] - windowY;
                if (view.getClipToOutline()) {
                    // Stays in effect for everything nested inside this view.
                    clipToOutline(canvas, view, left, top);
                }
                Drawable background = view.getBackground();
                if (background == null) {
                    continue;
                }
                int save = canvas.saveLayerAlpha(null, Math.round(view.getAlpha() * OPAQUE));
                canvas.translate(left, top);
                drawWithoutBlur(background, canvas);
                canvas.restoreToCount(save);
            }
            canvas.restoreToCount(base);
        }

        private static void clipToOutline(Canvas canvas, View view, float left, float top) {
            try {
                ViewOutlineProvider provider = view.getOutlineProvider();
                if (provider == null) {
                    return;
                }
                Outline outline = new Outline();
                provider.getOutline(view, outline);
                Path path = new Path();
                Rect rect = new Rect();
                if (outline.getRect(rect)) {
                    float radius = Math.max(0f, outline.getRadius());
                    path.addRoundRect(new RectF(rect), radius, radius, Path.Direction.CW);
                } else {
                    Field pathField = Outline.class.getDeclaredField("mPath");
                    pathField.setAccessible(true);
                    Path outlinePath = (Path) pathField.get(outline);
                    if (outlinePath == null || outlinePath.isEmpty()) {
                        return;
                    }
                    path.set(outlinePath);
                }
                path.offset(left, top);
                canvas.clipPath(path);
            } catch (Throwable ignored) {
                // An outline that cannot be read is treated as not clipping.
            }
        }

        /**
         * Works out what the keyboard panel leaves unpainted at its two bottom corners.
         * Each corner bitmap ends up holding the keyboard color exactly where the panel's
         * rounded corner leaves a gap, and nothing elsewhere.
         */
        private void measureCorners(View inputView, int color) {
            cornerRadiusPx = 0;
            if (Color.alpha(color) >= OPAQUE) {
                return;
            }
            List<View> stack = keyboardStack(inputView);
            int size = Math.round(CORNER_AREA_DP
                    * inputView.getResources().getDisplayMetrics().density);
            int width = inputView.getWidth();
            int stripTop = inputView.getHeight() - inputView.getPaddingBottom();
            if (stack.isEmpty() || size <= 0 || inputView.getPaddingBottom() <= 0
                    || width < size * 2 || stripTop < size) {
                return;
            }
            if (leftCorner == null || leftCorner.getWidth() != size) {
                leftCorner = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                rightCorner = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            }
            cornerAreaPx = size;
            inputView.getLocationInWindow(origin);
            float top = origin[1] + stripTop - size;
            renderCorner(leftCorner, stack, color, origin[0], top);
            renderCorner(rightCorner, stack, color, origin[0] + width - size, top);

            // The gap's height along the outer edge is the corner radius.
            int radius = 0;
            for (int y = size - 1; y >= 0 && Color.alpha(leftCorner.getPixel(0, y)) > 0; y--) {
                radius++;
            }
            // A gap as tall as the whole area means the panel does not reach the screen edge
            // (floating or one-handed layouts), which is not a rounded corner to fill.
            cornerRadiusPx = radius >= size ? 0 : radius;
        }

        private void renderCorner(Bitmap corner, List<View> stack, int color,
                float windowX, float windowY) {
            corner.eraseColor(color);
            Canvas canvas = new Canvas(corner);
            int layer = canvas.saveLayer(null, cutOutPaint);
            drawStack(canvas, stack, windowX, windowY);
            canvas.restoreToCount(layer);
        }

        /**
         * Squares off the keyboard panel's rounded bottom corners and moves that rounding to
         * the bottom of the strip, so panel and strip read as one shape.
         */
        boolean drawSeamless(View inputView, Canvas canvas, int color) {
            if (cornerRadiusPx <= 0 || leftCorner == null) {
                return false;
            }
            int width = inputView.getWidth();
            int height = inputView.getHeight();
            int stripTop = height - inputView.getPaddingBottom();
            canvas.drawBitmap(leftCorner, 0, stripTop - cornerAreaPx, null);
            canvas.drawBitmap(rightCorner, width - cornerAreaPx, stripTop - cornerAreaPx, null);

            float radius = stripCornerRadius(inputView);
            stripPath.rewind();
            stripPath.addRoundRect(0, stripTop, width, height,
                    new float[]{0, 0, 0, 0, radius, radius, radius, radius}, Path.Direction.CW);
            fillPaint.setColor(color);
            canvas.drawPath(stripPath, fillPaint);
            return true;
        }

        private float stripCornerRadius(View inputView) {
            return Math.min(cornerRadiusPx, inputView.getPaddingBottom());
        }

        /**
         * Draws a background the way it ends up on screen. A background blur layer clears
         * everything painted beneath it in the same view (Frosted Glass stacks one on top of
         * the keyboard's original base background), so only the layers above it count. The
         * blur layer itself needs a hardware canvas and adds no color of its own.
         */
        private static void drawWithoutBlur(Drawable drawable, Canvas canvas) {
            if (isBlur(drawable)) {
                return;
            }
            if (drawable instanceof LayerDrawable) {
                LayerDrawable layers = (LayerDrawable) drawable;
                int first = 0;
                for (int index = 0; index < layers.getNumberOfLayers(); index++) {
                    Drawable layer = layers.getDrawable(index);
                    if (layer != null && isBlur(layer)) {
                        first = index + 1;
                    }
                }
                for (int index = first; index < layers.getNumberOfLayers(); index++) {
                    Drawable layer = layers.getDrawable(index);
                    if (layer != null) {
                        layer.draw(canvas);
                    }
                }
                return;
            }
            drawable.draw(canvas);
        }

        private static boolean isBlur(Drawable drawable) {
            return drawable.getClass().getName().contains("Blur");
        }

        /**
         * Blurs what is behind the strip, the way Frosted Glass blurs what is behind the keys.
         * Relies on the hidden API access Frosted Glass already enables for its own blur.
         */
        void drawBlur(View inputView, Canvas canvas) {
            if (!settings.enabled || blurUnavailable || Build.VERSION.SDK_INT < 31
                    || !canvas.isHardwareAccelerated()) {
                return;
            }
            try {
                if (blur == null) {
                    Method getViewRoot = View.class.getDeclaredMethod("getViewRootImpl");
                    getViewRoot.setAccessible(true);
                    Object viewRoot = getViewRoot.invoke(inputView);
                    if (viewRoot == null) {
                        return;
                    }
                    Method create = viewRoot.getClass()
                            .getDeclaredMethod("createBackgroundBlurDrawable");
                    create.setAccessible(true);
                    blur = (Drawable) create.invoke(viewRoot);
                    blur.setVisible(true, false);
                }
                Method setRadius = blur.getClass().getDeclaredMethod("setBlurRadius", int.class);
                setRadius.setAccessible(true);
                setRadius.invoke(blur, settings.blurRadiusPx);
                int width = inputView.getWidth();
                int height = inputView.getHeight();
                // Frosted Glass rounds all four corners of its blur, which leaves the two
                // bottom corners of the keyboard unblurred where they meet the strip. Reach up
                // behind the keyboard by that corner radius to fill them in.
                int overlap = Math.round(FROSTED_GLASS_CORNER_RADIUS_DP
                        * inputView.getResources().getDisplayMetrics().density);
                int top = Math.max(0, height - inputView.getPaddingBottom() - overlap);
                blur.setBounds(0, top, width, height);
                try {
                    float radius = cornerRadiusPx > 0 ? stripCornerRadius(inputView) : 0f;
                    Method setCorners = blur.getClass().getDeclaredMethod("setCornerRadius",
                            float.class, float.class, float.class, float.class);
                    setCorners.setAccessible(true);
                    setCorners.invoke(blur, 0f, 0f, radius, radius);
                } catch (Throwable ignored) {
                    // Square blur corners are an acceptable fallback.
                }
                blur.draw(canvas);
            } catch (Throwable ignored) {
                blur = null;
                blurUnavailable = true;
            }
        }

        void watch(View view) {
            inputView = new WeakReference<>(view);
            if (!attachListenerAdded) {
                view.addOnAttachStateChangeListener(this);
                attachListenerAdded = true;
            }
            if (!watching && view.isAttachedToWindow()) {
                view.getViewTreeObserver().addOnPreDrawListener(this);
                watching = true;
            }
        }

        @Override
        public boolean onPreDraw() {
            try {
                View view = inputView.get();
                // Child views are restyled without redrawing InputView, so redraw the strip
                // whenever the keyboard no longer matches what was painted.
                if (view != null && (keyboardColor(view, cachedFallback) != drawnColor
                        || cornerRadiusPx != drawnCornerRadius)) {
                    view.invalidate();
                }
            } catch (Throwable ignored) {
                // Never block drawing.
            }
            return true;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            watch(view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (watching) {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                watching = false;
            }
            // A blur drawable belongs to the window it was created for.
            blur = null;
            blurUnavailable = false;
        }

        private static View find(View view, String tag) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return null;
            }
            Object viewTag = view.getTag();
            if (viewTag instanceof String && ((String) viewTag).contains(tag)
                    && view.getBackground() != null && view.getWidth() > 0) {
                return view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    View match = find(group.getChildAt(index), tag);
                    if (match != null) {
                        return match;
                    }
                }
            }
            return null;
        }
    }
}
