package app.immersivekeyboard.extension;

import android.view.View;
import android.view.ViewGroup;

/**
 * Keeps Gboard's floating popups, such as the keyboard switcher shown while a hardware
 * keyboard is in use, inside the keyboard window.
 *
 * In the edge-to-edge mode the Immersive Keyboard patch turns on, the keyboard view fills the
 * screen and its keys start far down it. When a hardware keyboard hides that view, the window
 * shrinks to a strip at the bottom, but the switcher is still placed above where the keys
 * were: far above its own window, where it can neither be seen nor tapped.
 */
@SuppressWarnings("unused")
public final class FloatingWidget {
    private FloatingWidget() {
    }

    /** Injection point. Called right after Gboard positions a floating popup's container. */
    public static void positioned(View container) {
        try {
            keepInside(container);
            // Sizes are only final after the next layout pass.
            container.post(() -> keepInside(container));
        } catch (Throwable ignored) {
            // Never get in the way of the keyboard.
        }
    }

    private static void keepInside(View container) {
        if (!(container.getParent() instanceof View)) {
            return;
        }
        View window = (View) container.getParent();
        int windowHeight = window.getHeight();
        View content = container instanceof ViewGroup && ((ViewGroup) container).getChildCount() > 0
                ? ((ViewGroup) container).getChildAt(0) : container;
        int height = content.getHeight() > 0 ? content.getHeight() : content.getMeasuredHeight();
        if (windowHeight <= 0 || height <= 0) {
            return;
        }
        float y = container.getY();
        // Only popups that ended up entirely outside the window; anything Gboard placed so it
        // shows at least partly is left alone.
        if (y + height <= 0 || y >= windowHeight) {
            container.setY(Math.max(0, windowHeight - height));
            container.requestLayout();
        }
    }
}
