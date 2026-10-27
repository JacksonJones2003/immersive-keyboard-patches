package app.immersivekeyboard.extension;

import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stands in for Frosted Glass where it gives up. Frosted Glass only blurs a keyboard panel
 * whose outline is a plain rectangle or a rounded rectangle with one radius, and in
 * one-handed mode Gboard outlines its floating panel in another way, so Frosted Glass leaves
 * the whole keyboard solid. When that happens this applies the same blur and see-through
 * panels with Frosted Glass's own settings, so the glass and the bottom strip work as usual.
 */
final class FrostedFallback {
    private static final String ROOT_TAG = ".keyboard-base-area.keyboard-outline";
    private static final String FLOATING_ROOT_TAG = ".keyboard-base-area";

    /** Keyboard panels this has given a blur, with the background they had before. */
    private static final Map<View, Drawable[]> APPLIED = new WeakHashMap<>();

    private FrostedFallback() {
    }

    /**
     * Called while the keyboard draws. Blurs the keyboard panel if Frosted Glass is on but
     * cannot, and takes the blur away again once Frosted Glass can do it itself.
     */
    static void ensure(View inputView, boolean frostedEnabled, int blurRadiusPx, int surfaceAlpha,
            float cornerRadiusPx) {
        View root = find(inputView, ROOT_TAG);
        if (root == null) {
            root = find(inputView, FLOATING_ROOT_TAG);
        }
        if (root == null) {
            return;
        }
        Drawable[] applied = APPLIED.get(root);
        boolean ours = applied != null && root.getBackground() == applied[1];
        boolean needed = frostedEnabled && !frostedCanShape(root, ours ? applied[0]
                : root.getBackground());
        if (!needed) {
            if (ours) {
                root.setBackground(applied[0]);
            }
            APPLIED.remove(root);
            return;
        }
        if (!ours) {
            Drawable original = root.getBackground();
            if (containsBlur(original)) {
                return;
            }
            Drawable blur = createBlur(root, blurRadiusPx, cornerRadiusPx);
            if (blur == null) {
                return;
            }
            Drawable layered = original == null ? new LayerDrawable(new Drawable[]{blur})
                    : new LayerDrawable(new Drawable[]{original, blur});
            root.setBackground(layered);
            APPLIED.put(root, new Drawable[]{original, layered});
        }
        if (surfaceAlpha >= 0) {
            applySurfaceAlpha(inputView, surfaceAlpha);
        }
    }

    /** The same shape check Frosted Glass makes before it blurs a panel. */
    private static boolean frostedCanShape(View root, Drawable background) {
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            return true;
        }
        ViewOutlineProvider provider = root.getOutlineProvider();
        Outline outline = new Outline();
        if (root.getClipToOutline() && provider != null
                && provider != ViewOutlineProvider.BACKGROUND) {
            provider.getOutline(root, outline);
        } else if (background != null) {
            background.getOutline(outline);
        } else {
            return true;
        }
        Rect rect = new Rect();
        float radius = outline.getRadius();
        return outline.getRect(rect) && !rect.isEmpty() && radius >= 0
                && !Float.isNaN(radius) && !Float.isInfinite(radius);
    }

    /** The see-through panels Frosted Glass sets to its custom opacity. */
    private static void applySurfaceAlpha(View view, int alpha) {
        Object tag = view.getTag();
        if (tag instanceof String) {
            String text = (String) tag;
            Drawable background = view.getBackground();
            if (background != null && background.getAlpha() != alpha
                    && (text.contains(".keyboard-header-area")
                    || text.contains(".keyboard-body-area")
                    || text.startsWith(".candidates-area"))) {
                background.setAlpha(alpha);
                view.invalidate();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applySurfaceAlpha(group.getChildAt(index), alpha);
            }
        }
    }

    private static boolean containsBlur(Drawable drawable) {
        if (drawable == null) {
            return false;
        }
        if (drawable.getClass().getName().endsWith("BackgroundBlurDrawable")) {
            return true;
        }
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int index = 0; index < layers.getNumberOfLayers(); index++) {
                if (containsBlur(layers.getDrawable(index))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Drawable createBlur(View view, int radiusPx, float cornerRadiusPx) {
        try {
            Method getViewRoot = View.class.getDeclaredMethod("getViewRootImpl");
            getViewRoot.setAccessible(true);
            Object viewRoot = getViewRoot.invoke(view);
            if (viewRoot == null) {
                return null;
            }
            Method create = viewRoot.getClass().getDeclaredMethod("createBackgroundBlurDrawable");
            create.setAccessible(true);
            Drawable blur = (Drawable) create.invoke(viewRoot);
            blur.setVisible(true, false);
            Method setRadius = blur.getClass().getDeclaredMethod("setBlurRadius", int.class);
            setRadius.setAccessible(true);
            setRadius.invoke(blur, Math.max(1, radiusPx));
            Method setCorner = blur.getClass().getDeclaredMethod("setCornerRadius", float.class);
            setCorner.setAccessible(true);
            setCorner.invoke(blur, cornerRadiusPx);
            return blur;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View find(View view, String tag) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (tag.equals(view.getTag()) && view.getWidth() > 0 && view.getHeight() > 0) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = find(group.getChildAt(index), tag);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
