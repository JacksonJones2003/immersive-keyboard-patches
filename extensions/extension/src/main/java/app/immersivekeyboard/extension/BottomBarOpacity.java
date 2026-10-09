package app.immersivekeyboard.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.ref.WeakReference;
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
    private static final String HEADER_AREA_TAG = ".keyboard-header-area";
    private static final int NO_TOP = Integer.MAX_VALUE;
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
    private static final String ROUNDED_PANEL_ENABLED = "pref_rounded_keyboard_panel_enabled";
    private static final String ROUNDED_PANEL_MODE = "pref_rounded_keyboard_panel_mode";
    private static final String ROUNDED_PANEL_BOTTOM_RADIUS =
            "pref_rounded_keyboard_panel_bottom_radius_dp";

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
            if (Color.alpha(keyboardColor) >= OPAQUE) {
                return color;
            }
            state.drawBlur(inputView, canvas);
            return keyboardColor;
        } catch (Throwable ignored) {
            return color;
        }
    }

    /**
     * Injection point. Called from InputView.dispatchDraw once the keyboard itself has been
     * drawn, so anything painted here lands on top of it.
     */
    public static void afterKeyboardDrawn(View inputView, Canvas canvas) {
        try {
            State state = STATES.get(inputView);
            if (state != null) {
                state.repaintBottomCorners(inputView, canvas);
                state.repaintTopCorners(inputView, canvas);
            }
        } catch (Throwable ignored) {
            // Never block drawing.
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
        static final FrostedGlass OFF =
                new FrostedGlass(false, -1, 0, FROSTED_GLASS_CORNER_RADIUS_DP);

        final boolean enabled;
        /** Alpha Frosted Glass applies to keyboard surfaces in custom mode, otherwise -1. */
        final int customAlpha;
        final int blurRadiusPx;
        /** Radius of the rounded bottom corners of the keyboard's see-through area. */
        final float cornerRadiusDp;

        private FrostedGlass(boolean enabled, int customAlpha, int blurRadiusPx,
                float cornerRadiusDp) {
            this.cornerRadiusDp = cornerRadiusDp;
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
                // Rounded Keyboard Panel can round the same corners by more than Frosted Glass.
                float corner = FROSTED_GLASS_CORNER_RADIUS_DP;
                if ("true".equalsIgnoreCase(String.valueOf(values.get(ROUNDED_PANEL_ENABLED)))
                        && !"top".equals(values.get(ROUNDED_PANEL_MODE))) {
                    corner = Math.max(corner, clamp(number(
                            values.get(ROUNDED_PANEL_BOTTOM_RADIUS), 32), 0, 64));
                }
                return new FrostedGlass(true, customAlpha, radius, corner);
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
        private Drawable topBlur;
        private int topColor;
        private int keyboardTop = NO_TOP;
        private final int[] origin = new int[2];
        private final Path cornerPath = new Path();
        private final Path circlePath = new Path();
        private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint erasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        State() {
            erasePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }
        private WeakReference<Drawable> squaredBlur = new WeakReference<>(null);
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
            squareKeyboardBlurBottom(inputView);
            keyboardTop = findKeyboardTop(inputView);
            topColor = keyboardTop == NO_TOP ? 0 : sampleTop(inputView);
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
            return cachedColor;
        }

        /**
         * Paints the backgrounds stacked under the keys into a single pixel, in the order the
         * keyboard draws them, and returns the result. Whatever the theme and Frosted Glass
         * did to those backgrounds (color, alpha, layering) is reflected in that pixel.
         */
        private int sampleKeyboard(View inputView) {
            try {
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
                    return 0;
                }
                List<View> stack = new ArrayList<>();
                for (View view = target; view != null && view != inputView; ) {
                    stack.add(view);
                    ViewParent parent = view.getParent();
                    view = parent instanceof View ? (View) parent : null;
                }
                target.getLocationInWindow(location);
                float sampleX = location[0] + target.getWidth() / 2f;
                float sampleY = location[1] + target.getHeight() / 2f;
                // Panels nested inside the body surface add their own tint over it. Follow
                // them down, stopping before individual keys.
                List<View> nested = new ArrayList<>();
                View current = target;
                while (current instanceof ViewGroup) {
                    View next = panelAt((ViewGroup) current, sampleX, sampleY,
                            target.getWidth() * 0.8f);
                    if (next == null) {
                        break;
                    }
                    nested.add(next);
                    current = next;
                }
                for (View view : nested) {
                    stack.add(0, view);
                }

                pixel.eraseColor(Color.TRANSPARENT);
                for (int index = stack.size() - 1; index >= 0; index--) {
                    View view = stack.get(index);
                    Drawable background = view.getBackground();
                    if (background == null || view.getVisibility() != View.VISIBLE) {
                        continue;
                    }
                    view.getLocationInWindow(location);
                    int save = pixelCanvas.saveLayerAlpha(0, 0, 1, 1,
                            Math.round(view.getAlpha() * OPAQUE));
                    pixelCanvas.translate(location[0] - sampleX, location[1] - sampleY);
                    drawWithoutBlur(background, pixelCanvas);
                    pixelCanvas.restoreToCount(save);
                }
                return pixel.getPixel(0, 0);
            } catch (Throwable ignored) {
                return 0;
            }
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
         * Frosted Glass rounds all four corners of the blur it puts on the keyboard panel.
         * Outside those rounded corners the panel's original solid background stays visible,
         * which shows up as a dark wedge in each corner. The bottom two sit right on the
         * strip, so make the bottom of that blur square and let it cover them.
         */
        private void squareKeyboardBlurBottom(View inputView) {
            if (!settings.enabled) {
                return;
            }
            try {
                Drawable keyboardBlur = findBlur(inputView.getRootView());
                if (keyboardBlur == null || keyboardBlur == squaredBlur.get()) {
                    return;
                }
                float radius = FROSTED_GLASS_CORNER_RADIUS_DP
                        * inputView.getResources().getDisplayMetrics().density;
                Method setCorners = keyboardBlur.getClass().getDeclaredMethod("setCornerRadius",
                        float.class, float.class, float.class, float.class);
                setCorners.setAccessible(true);
                setCorners.invoke(keyboardBlur, radius, radius, 0f, 0f);
                squaredBlur = new WeakReference<>(keyboardBlur);
            } catch (Throwable ignored) {
                // Leave the keyboard's blur as Frosted Glass made it.
            }
        }

        /** The blur layer Frosted Glass added to a view's background, if any. */
        private Drawable findBlur(View view) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return null;
            }
            Drawable background = view.getBackground();
            if (background instanceof LayerDrawable) {
                LayerDrawable layers = (LayerDrawable) background;
                for (int index = 0; index < layers.getNumberOfLayers(); index++) {
                    Drawable layer = layers.getDrawable(index);
                    if (layer != null && layer != blur && isBlur(layer)) {
                        return layer;
                    }
                }
            } else if (background != null && background != blur && isBlur(background)) {
                return background;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    Drawable match = findBlur(group.getChildAt(index));
                    if (match != null) {
                        return match;
                    }
                }
            }
            return null;
        }

        /**
         * The translucent, blurred part of the keyboard is a rectangle with all four corners
         * rounded, so its two bottom corners leave small wedges of something else where the
         * keyboard meets the strip. Erase those wedges from the finished keyboard and repaint
         * them like the strip (blurred backdrop plus keyboard color), which makes the bottom
         * edge of the keyboard run straight into it.
         */
        void repaintBottomCorners(View inputView, Canvas canvas) {
            int padding = inputView.getPaddingBottom();
            View keyboard = surface.get();
            if (!settings.enabled || padding <= 0 || Color.alpha(drawnColor) >= OPAQUE
                    || blur == null || keyboard == null
                    // Floating and one-handed keyboards do not sit on the strip's corners.
                    || keyboard.getWidth() < inputView.getWidth() * 0.95f) {
                return;
            }
            float radius = settings.cornerRadiusDp
                    * inputView.getResources().getDisplayMetrics().density;
            float width = inputView.getWidth();
            float stripTop = inputView.getHeight() - padding;
            float top = stripTop - radius;

            // Each wedge is a corner square minus the quarter circle the keyboard fills.
            cornerPath.rewind();
            cornerPath.addRect(0, top, radius, stripTop, Path.Direction.CW);
            cornerPath.addRect(width - radius, top, width, stripTop, Path.Direction.CW);
            circlePath.rewind();
            circlePath.addCircle(radius, top, radius, Path.Direction.CW);
            circlePath.addCircle(width - radius, top, radius, Path.Direction.CW);
            cornerPath.op(circlePath, Path.Op.DIFFERENCE);

            canvas.drawPath(cornerPath, erasePaint);
            cornerPaint.setColor(drawnColor);
            canvas.drawPath(cornerPath, cornerPaint);
        }

        /**
         * Same treatment for the keyboard's two top corners: erase the wedges outside the
         * rounded see-through area and repaint them blurred and tinted like the top of the
         * keyboard, giving it a straight top edge.
         */
        void repaintTopCorners(View inputView, Canvas canvas) {
            View keyboard = surface.get();
            if (!settings.enabled || topBlur == null || keyboard == null
                    || Color.alpha(drawnColor) >= OPAQUE
                    || keyboard.getWidth() < inputView.getWidth() * 0.95f) {
                return;
            }
            float radius = settings.cornerRadiusDp
                    * inputView.getResources().getDisplayMetrics().density;
            float width = inputView.getWidth();
            if (keyboardTop == NO_TOP) {
                return;
            }
            float top = topInInputView(inputView);
            float bottom = top + radius;

            cornerPath.rewind();
            cornerPath.addRect(0, top, radius, bottom, Path.Direction.CW);
            cornerPath.addRect(width - radius, top, width, bottom, Path.Direction.CW);
            circlePath.rewind();
            circlePath.addCircle(radius, bottom, radius, Path.Direction.CW);
            circlePath.addCircle(width - radius, bottom, radius, Path.Direction.CW);
            cornerPath.op(circlePath, Path.Op.DIFFERENCE);

            canvas.drawPath(cornerPath, erasePaint);
            cornerPaint.setColor(Color.alpha(topColor) > 0 && Color.alpha(topColor) < OPAQUE
                    ? topColor : drawnColor);
            canvas.drawPath(cornerPath, cornerPaint);
        }

        /** Top edge of the visible keyboard, in InputView coordinates. */
        private float topInInputView(View inputView) {
            inputView.getLocationInWindow(origin);
            return keyboardTop - origin[1];
        }

        /**
         * Window Y of the top edge of the visible keyboard: the highest of its themed header
         * and body panels. The keyboard window itself can be as tall as the screen, so its own
         * top edge says nothing about where the keyboard starts.
         */
        private int findKeyboardTop(View inputView) {
            int[] top = {NO_TOP};
            collectKeyboardTop(inputView, inputView.getWidth() * 0.95f, top);
            return top[0];
        }

        private void collectKeyboardTop(View view, float minWidth, int[] top) {
            if (view.getVisibility() != View.VISIBLE) {
                return;
            }
            Object tag = view.getTag();
            if (tag instanceof String && view.getBackground() != null
                    && view.getWidth() >= minWidth && view.getHeight() > 0
                    && (((String) tag).contains(HEADER_AREA_TAG)
                    || ((String) tag).contains(BODY_AREA_TAG))) {
                view.getLocationInWindow(location);
                top[0] = Math.min(top[0], location[1]);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    collectKeyboardTop(group.getChildAt(index), minWidth, top);
                }
            }
        }

        /** Color of the keyboard just inside its top edge, where the top corners are. */
        private int sampleTop(View panel) {
            try {
                float radius = settings.cornerRadiusDp
                        * panel.getResources().getDisplayMetrics().density;
                panel.getLocationInWindow(location);
                float sampleX = location[0] + panel.getWidth() / 2f;
                float sampleY = keyboardTop + radius / 2f;
                List<View> stack = new ArrayList<>();
                stack.add(panel);
                View current = panel;
                while (current instanceof ViewGroup) {
                    View next = panelAt((ViewGroup) current, sampleX, sampleY,
                            panel.getWidth() * 0.8f);
                    if (next == null) {
                        break;
                    }
                    stack.add(next);
                    current = next;
                }
                pixel.eraseColor(Color.TRANSPARENT);
                for (View view : stack) {
                    Drawable background = view.getBackground();
                    if (background == null) {
                        continue;
                    }
                    view.getLocationInWindow(location);
                    int save = pixelCanvas.saveLayerAlpha(0, 0, 1, 1,
                            Math.round(view.getAlpha() * OPAQUE));
                    pixelCanvas.translate(location[0] - sampleX, location[1] - sampleY);
                    drawWithoutBlur(background, pixelCanvas);
                    pixelCanvas.restoreToCount(save);
                }
                return pixel.getPixel(0, 0);
            } catch (Throwable ignored) {
                return 0;
            }
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
                int overlap = Math.round(settings.cornerRadiusDp
                        * inputView.getResources().getDisplayMetrics().density);
                int top = Math.max(0, height - inputView.getPaddingBottom() - overlap);
                blur.setBounds(0, top, width, height);
                blur.draw(canvas);

                // A second blur region behind the keyboard's top corners.
                if (keyboardTop != NO_TOP) {
                    if (topBlur == null) {
                        Method getRoot = View.class.getDeclaredMethod("getViewRootImpl");
                        getRoot.setAccessible(true);
                        Object root = getRoot.invoke(inputView);
                        Method createBlur = root.getClass()
                                .getDeclaredMethod("createBackgroundBlurDrawable");
                        createBlur.setAccessible(true);
                        topBlur = (Drawable) createBlur.invoke(root);
                        topBlur.setVisible(true, false);
                    }
                    setRadius.invoke(topBlur, settings.blurRadiusPx);
                    int keyboardTopY = Math.round(topInInputView(inputView));
                    topBlur.setBounds(0, keyboardTopY, width, keyboardTopY + overlap);
                    topBlur.draw(canvas);
                }
            } catch (Throwable ignored) {
                blur = null;
                topBlur = null;
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
                if (view != null && keyboardColor(view, cachedFallback) != drawnColor) {
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
            topBlur = null;
            blurUnavailable = false;
        }

        /** A visible child spanning most of the keyboard's width that covers the point. */
        private View panelAt(ViewGroup parent, float windowX, float windowY, float minWidth) {
            for (int index = parent.getChildCount() - 1; index >= 0; index--) {
                View child = parent.getChildAt(index);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() < minWidth) {
                    continue;
                }
                child.getLocationInWindow(location);
                if (windowX >= location[0] && windowX < location[0] + child.getWidth()
                        && windowY >= location[1] && windowY < location[1] + child.getHeight()) {
                    return child;
                }
            }
            return null;
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
