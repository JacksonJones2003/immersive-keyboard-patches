package app.immersivekeyboard.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
