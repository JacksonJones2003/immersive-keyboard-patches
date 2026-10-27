package app.immersivekeyboard.extension;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps Gboard's floating popups, such as the keyboard switcher shown while a hardware
 * keyboard is in use and the menu it opens, inside the keyboard window.
 *
 * In the edge-to-edge mode the Immersive Keyboard patch turns on, the keyboard view fills the
 * screen and Gboard places popups relative to it. When a hardware keyboard hides that view,
 * the window shrinks to fit the switcher at the bottom of the screen, but the switcher is
 * still placed above where the keys were, and its menu opens above the switcher: both outside
 * the window, where they can neither be seen nor tapped.
 */
@SuppressWarnings("unused")
public final class FloatingWidget {
    /** How far each popup layer has been grown upward to fit its popups, in pixels. */
    private static final Map<View, Integer> GROWN = new WeakHashMap<>();

    private FloatingWidget() {
    }

    /** Injection point. Called right after Gboard positions a floating popup's container. */
    public static void positioned(View container) {
        try {
            keepInside(container);
            if (container.getParent() instanceof ViewGroup) {
                watch((ViewGroup) container.getParent());
            }
            // Sizes are only final after the next layout pass.
            container.post(() -> {
                keepInside(container);
                if (container.getParent() instanceof ViewGroup) {
                    fit((ViewGroup) container.getParent());
                }
            });
        } catch (Throwable ignored) {
            // Never get in the way of the keyboard.
        }
    }

    /**
     * Moves a popup that was placed entirely outside the window, like the switcher, to the
     * bottom of the window, which is the bottom of the screen.
     */
    private static void keepInside(View container) {
        if (!(container.getParent() instanceof View)) {
            return;
        }
        View layer = (View) container.getParent();
        int layerHeight = layer.getHeight();
        View content = content(container);
        int height = content.getHeight() > 0 ? content.getHeight() : content.getMeasuredHeight();
        if (layerHeight <= 0 || height <= 0) {
            return;
        }
        float y = container.getY();
        // Only popups that ended up entirely outside the window; anything Gboard placed so it
        // shows at least partly is left alone.
        if (y + height <= 0 || y >= layerHeight) {
            container.setY(Math.max(0, layerHeight - height));
            container.requestLayout();
        }
    }

    private static void watch(ViewGroup layer) {
        if (GROWN.containsKey(layer)) {
            return;
        }
        GROWN.put(layer, 0);
        layer.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                try {
                    // Returning false skips a frame that would show popups in the wrong place.
                    return !fit(layer);
                } catch (Throwable ignored) {
                    return true;
                }
            }
        });
    }

    /**
     * Grows the popup layer, and with it the keyboard window, upward when a popup sticks out
     * above it, and shrinks it again once nothing needs the room. Every popup is moved by the
     * same amount the layer's top edge moves, so nothing shifts on screen. Returns whether the
     * layer changed.
     */
    private static boolean fit(ViewGroup layer) {
        int height = layer.getHeight();
        if (height <= 0) {
            return false;
        }
        float top = Float.MAX_VALUE;
        for (int index = 0; index < layer.getChildCount(); index++) {
            View container = layer.getChildAt(index);
            View content = content(container);
            if (container.getVisibility() != View.VISIBLE || content.getHeight() <= 0
                    || content.getVisibility() != View.VISIBLE) {
                continue;
            }
            float contentTop = container.getY() + content.getTop() + content.getTranslationY();
            top = Math.min(top, contentTop);
        }
        int grown = GROWN.containsKey(layer) ? GROWN.get(layer) : 0;
        int shift;
        if (top < -0.5f) {
            int screen = layer.getResources().getDisplayMetrics().heightPixels;
            shift = Math.min((int) Math.ceil(-top), Math.max(0, screen - height));
        } else if (grown > 0) {
            // Give back the room nothing uses any more.
            shift = -Math.min(grown, top == Float.MAX_VALUE ? grown : (int) Math.floor(top));
        } else {
            return false;
        }
        if (shift == 0) {
            return false;
        }
        for (int index = 0; index < layer.getChildCount(); index++) {
            View container = layer.getChildAt(index);
            View content = content(container);
            float offset = content.getTop() + content.getTranslationY();
            if (shift > 0 && content != container && offset < 0) {
                // Gboard lifts a menu above its container by translating it. Touches only
                // reach what lies inside the container, so bring the menu back into it
                // rather than moving the container: same place on screen, but tappable.
                float intoContainer = Math.min(shift, -offset);
                content.setTranslationY(content.getTranslationY() + intoContainer);
                container.setY(container.getY() + shift - intoContainer);
            } else {
                container.setY(container.getY() + shift);
            }
        }
        grown += shift;
        GROWN.put(layer, grown);
        layer.setMinimumHeight(grown > 0 ? height + shift : 0);
        layer.requestLayout();
        return true;
    }

    private static View content(View container) {
        return container instanceof ViewGroup && ((ViewGroup) container).getChildCount() > 0
                ? ((ViewGroup) container).getChildAt(0) : container;
    }
}
