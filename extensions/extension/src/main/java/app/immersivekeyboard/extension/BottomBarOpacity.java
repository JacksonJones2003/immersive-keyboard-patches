package app.immersivekeyboard.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Makes the strip Gboard paints behind the system navigation bar follow the opacity of the
 * keyboard's themed surfaces, so a translucent keyboard is not left with a solid bottom bar.
 */
@SuppressWarnings("unused")
public final class BottomBarOpacity {
    private static final String BASE_AREA_TAG = ".keyboard-base-area";
    private static final String BODY_AREA_TAG = ".keyboard-body-area";
    private static final int OPAQUE = 255;
    private static final long SEARCH_INTERVAL_MS = 500;
    private static final String FROSTED_GLASS_PREFERENCES = "gboard_patches_settings";
    private static final String FROSTED_GLASS_ENABLED = "pref_frosted_glass_enabled";
    private static final String FROSTED_GLASS_TRANSPARENCY_MODE =
            "pref_frosted_glass_transparency_mode";
    private static final String FROSTED_GLASS_CUSTOM_OPACITY = "pref_frosted_glass_custom_opacity";
    private static final int FROSTED_GLASS_DEFAULT_OPACITY = 20;

    private static final Map<View, State> STATES = new WeakHashMap<>();

    private BottomBarOpacity() {
    }

    /**
     * Injection point. Called from InputView.onDraw with the color of the bottom strip.
     */
    public static int stripColor(View inputView, int color) {
        try {
            State state = stateFor(inputView);
            int alpha = state.surfaceAlpha(inputView);
            state.drawnAlpha = alpha;
            state.watch(inputView);
            // A color that is already translucent was scaled by navigationBarColor.
            if (alpha >= OPAQUE || Color.alpha(color) != OPAQUE) {
                return color;
            }
            int scaled = Color.alpha(color) * alpha / OPAQUE;
            return (color & 0x00FFFFFF) | (scaled << 24);
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
            int alpha = State.frostedGlassAlpha(window.getContext());
            if (alpha < 0 || alpha >= OPAQUE || Color.alpha(color) != OPAQUE) {
                return color;
            }
            return (color & 0x00FFFFFF) | (alpha << 24);
        } catch (Throwable ignored) {
            return color;
        }
    }

    private static State stateFor(View inputView) {
        State state = STATES.get(inputView);
        if (state == null) {
            state = new State();
            STATES.put(inputView, state);
        }
        return state;
    }

    private static final class State implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        int drawnAlpha = OPAQUE;
        private WeakReference<View> inputView = new WeakReference<>(null);
        private WeakReference<View> baseArea = new WeakReference<>(null);
        private WeakReference<View> bodyArea = new WeakReference<>(null);
        private long lastSearch;
        private long lastSettingsRead = -SEARCH_INTERVAL_MS;
        private int configuredAlpha = -1;
        private boolean watching;
        private boolean attachListenerAdded;

        /**
         * Opacity of the keyboard as it appears on screen: the base surface with the body
         * surface layered over it.
         */
        int surfaceAlpha(View inputView) {
            long now = SystemClock.uptimeMillis();
            if (now - lastSettingsRead >= SEARCH_INTERVAL_MS) {
                lastSettingsRead = now;
                configuredAlpha = frostedGlassAlpha(inputView.getContext());
            }
            if (configuredAlpha >= 0) {
                return configuredAlpha;
            }
            View base = baseArea.get();
            View body = bodyArea.get();
            boolean stale = base == null || !base.isAttachedToWindow() || !base.isShown()
                    || (body != null && !body.isAttachedToWindow());
            if (stale && now - lastSearch >= SEARCH_INTERVAL_MS) {
                lastSearch = now;
                View root = inputView.getRootView();
                base = find(root, BASE_AREA_TAG, true);
                body = find(root, BODY_AREA_TAG, false);
                baseArea = new WeakReference<>(base);
                bodyArea = new WeakReference<>(body);
            }
            // No themed surface found: leave the strip as Gboard drew it.
            if (base == null && body == null) {
                return OPAQUE;
            }
            int transparency = OPAQUE;
            transparency = transparency * (OPAQUE - alphaOf(base)) / OPAQUE;
            transparency = transparency * (OPAQUE - alphaOf(body)) / OPAQUE;
            return OPAQUE - transparency;
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
                // whenever the keyboard's opacity no longer matches what was painted.
                if (view != null && surfaceAlpha(view) != drawnAlpha) {
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
        }

        /**
         * Alpha that the Frosted Glass patch from jasonwu1994/Gboard-patches applies to the
         * keyboard surfaces when its custom transparency is in use, or -1 when it is not.
         */
        static int frostedGlassAlpha(Context context) {
            try {
                Context application = context.getApplicationContext();
                SharedPreferences preferences = (application == null ? context : application)
                        .getSharedPreferences(FROSTED_GLASS_PREFERENCES, Context.MODE_PRIVATE);
                Map<String, ?> values = preferences.getAll();
                if (!"true".equalsIgnoreCase(String.valueOf(values.get(FROSTED_GLASS_ENABLED)))
                        || !"custom".equals(values.get(FROSTED_GLASS_TRANSPARENCY_MODE))) {
                    return -1;
                }
                Object raw = values.get(FROSTED_GLASS_CUSTOM_OPACITY);
                int transparency = raw instanceof Number
                        ? ((Number) raw).intValue()
                        : raw == null ? FROSTED_GLASS_DEFAULT_OPACITY
                                : Integer.parseInt(String.valueOf(raw));
                transparency = Math.max(0, Math.min(100, transparency));
                return Math.round((100 - transparency) * 255f / 100f);
            } catch (Throwable ignored) {
                return -1;
            }
        }

        private static int alphaOf(View view) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return 0;
            }
            return Math.round(alphaOf(view.getBackground(), 0) * view.getAlpha());
        }

        /**
         * Alpha a drawable is painted with, including translucency baked into its color.
         */
        private static int alphaOf(Drawable drawable, int depth) {
            if (drawable == null || depth > 4) {
                return 0;
            }
            if (drawable instanceof ColorDrawable) {
                return drawable.getAlpha();
            }
            if (drawable instanceof GradientDrawable) {
                ColorStateList color = ((GradientDrawable) drawable).getColor();
                int colorAlpha = color == null ? OPAQUE : Color.alpha(color.getDefaultColor());
                return colorAlpha * drawable.getAlpha() / OPAQUE;
            }
            if (drawable instanceof DrawableWrapper) {
                return alphaOf(((DrawableWrapper) drawable).getDrawable(), depth + 1);
            }
            if (drawable instanceof LayerDrawable) {
                LayerDrawable layers = (LayerDrawable) drawable;
                int transparency = OPAQUE;
                for (int index = 0; index < layers.getNumberOfLayers(); index++) {
                    Drawable layer = layers.getDrawable(index);
                    // A background blur layer shows what is behind it; it adds no opacity.
                    if (layer != null && layer.getClass().getName().contains("Blur")) {
                        continue;
                    }
                    int layerAlpha = alphaOf(layer, depth + 1);
                    transparency = transparency * (OPAQUE - layerAlpha) / OPAQUE;
                }
                return OPAQUE - transparency;
            }
            if (drawable.getOpacity() == PixelFormat.TRANSPARENT) {
                return 0;
            }
            return drawable.getAlpha();
        }

        private static View find(View view, String tag, boolean prefix) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return null;
            }
            Object viewTag = view.getTag();
            if (viewTag instanceof String && view.getBackground() != null) {
                String value = (String) viewTag;
                if (prefix ? value.startsWith(tag) : value.contains(tag)) {
                    return view;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    View match = find(group.getChildAt(index), tag, prefix);
                    if (match != null) {
                        return match;
                    }
                }
            }
            return null;
        }
    }
}
