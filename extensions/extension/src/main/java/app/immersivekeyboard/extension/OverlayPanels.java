package app.immersivekeyboard.extension;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gives the panels Gboard opens over the keyboard, such as the toolbar's "more" menu, the
 * same translucent tint as the toolbar. These panels live outside the keyboard view, so the
 * Frosted Glass transparency never reaches them and they cover the glass with a solid color.
 */
final class OverlayPanels {
    private static final String BODY_AREA_TAG = ".keyboard-body-area";

    /** Roots already being watched. */
    private static final Map<View, Boolean> WATCHED = new WeakHashMap<>();
    /** Panels and the background this class gave them. */
    private static final Map<View, Drawable> TINTED = new WeakHashMap<>();

    private OverlayPanels() {
    }

    /** Called whenever the keyboard draws; watches its window for panels opening. */
    static void watch(View inputView) {
        View root = inputView.getRootView();
        if (root == null || WATCHED.containsKey(root)) {
            return;
        }
        WATCHED.put(root, Boolean.TRUE);
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            try {
                match(inputView, root);
            } catch (Throwable ignored) {
                // Never get in the way of the keyboard.
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        listener.onGlobalLayout();
    }

    private static void match(View inputView, View root) {
        Drawable tint = toolbarTint(inputView);
        if (tint == null) {
            return;
        }
        tintPanels(root, inputView, tint);
    }

    /** The toolbar's background, when the keyboard is see-through. */
    private static Drawable toolbarTint(View inputView) {
        View header = find(inputView, BottomBarOpacity.HEADER_AREA_TAG);
        Drawable background = header == null ? null : header.getBackground();
        if (background == null || background.getAlpha() >= 255
                || background.getConstantState() == null) {
            return null;
        }
        return background;
    }

    private static void tintPanels(View view, View inputView, Drawable tint) {
        if (view == inputView || view.getVisibility() != View.VISIBLE) {
            return;
        }
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).contains(BODY_AREA_TAG)
                && view.getClass().getName().endsWith("SoftKeyboardView")) {
            Drawable current = view.getBackground();
            if (current != null && current != TINTED.get(view)) {
                Drawable copy = tint.getConstantState()
                        .newDrawable(view.getResources()).mutate();
                copy.setAlpha(tint.getAlpha());
                view.setBackground(copy);
                TINTED.put(view, copy);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                tintPanels(group.getChildAt(index), inputView, tint);
            }
        }
    }

    private static View find(View view, String tagPart) {
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).contains(tagPart)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = find(group.getChildAt(index), tagPart);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
