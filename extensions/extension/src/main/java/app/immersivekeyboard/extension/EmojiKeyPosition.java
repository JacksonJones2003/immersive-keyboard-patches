package app.immersivekeyboard.extension;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Moves the dedicated emoji key (the slot Gboard shares with the language switch key) from
 * the left of the spacebar to just left of the enter / search key.
 */
@SuppressWarnings("unused")
public final class EmojiKeyPosition {
    // Layout slot names from Gboard's bottom row layouts.
    private static final String EMOJI_KEY_SLOT = "key_pos_switch_to_next_language";
    private static final String ACTION_KEY_SLOT = "key_pos_ime_action";

    private static final Map<View, Watcher> WATCHERS = new WeakHashMap<>();

    private EmojiKeyPosition() {
    }

    /**
     * Injection point. Called from InputView.dispatchDraw; keeps one layout listener on the
     * keyboard window so every keyboard that gets shown in it is adjusted.
     */
    public static void attach(View inputView) {
        try {
            Watcher watcher = WATCHERS.get(inputView);
            if (watcher == null) {
                watcher = new Watcher(inputView);
                WATCHERS.put(inputView, watcher);
            }
            watcher.ensureListening();
        } catch (Throwable ignored) {
            // Never block drawing.
        }
    }

    private static final class Watcher implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<View> inputView;
        private WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
        private int emojiSlotId;
        private int actionSlotId;

        Watcher(View inputView) {
            this.inputView = new WeakReference<>(inputView);
        }

        void ensureListening() {
            View view = inputView.get();
            if (view == null || !view.isAttachedToWindow()) {
                return;
            }
            ViewTreeObserver current = view.getViewTreeObserver();
            if (observer.get() != current || !current.isAlive()) {
                current.addOnGlobalLayoutListener(this);
                observer = new WeakReference<>(current);
                // The keyboard that triggered this draw is already laid out.
                view.post(this::onGlobalLayout);
            }
        }

        @Override
        public void onGlobalLayout() {
            try {
                View view = inputView.get();
                if (view == null || !resolveSlots(view)) {
                    return;
                }
                List<View> emojiKeys = new ArrayList<>();
                collect(view.getRootView(), emojiSlotId, emojiKeys);
                for (View key : emojiKeys) {
                    moveBeforeActionKey(key);
                }
            } catch (Throwable ignored) {
                // Leave the keyboard as Gboard laid it out.
            }
        }

        private boolean resolveSlots(View view) {
            if (emojiSlotId != 0 && actionSlotId != 0) {
                return true;
            }
            Resources resources = view.getResources();
            // The resource package keeps its original name even when the app is renamed.
            String resourcePackage = view.getId() != View.NO_ID
                    ? resources.getResourcePackageName(view.getId())
                    : view.getContext().getPackageName();
            emojiSlotId = resources.getIdentifier(EMOJI_KEY_SLOT, "id", resourcePackage);
            actionSlotId = resources.getIdentifier(ACTION_KEY_SLOT, "id", resourcePackage);
            return emojiSlotId != 0 && actionSlotId != 0;
        }

        private static void collect(View view, int id, List<View> out) {
            if (view.getId() == id) {
                out.add(view);
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    collect(group.getChildAt(index), id, out);
                }
            }
        }

        /**
         * Takes the key out of its slot beside the spacebar and puts it directly before the
         * enter / search key of the same keyboard. Rows size their keys by weight and the two
         * rows use different scales, so the key keeps the width it was laid out with.
         */
        private void moveBeforeActionKey(View key) {
            if (key.getVisibility() != View.VISIBLE || key.getWidth() <= 0
                    || !(key.getParent() instanceof ViewGroup)) {
                return;
            }
            View actionKey = null;
            for (ViewParent parent = key.getParent(); parent instanceof View && actionKey == null;
                    parent = parent.getParent()) {
                actionKey = ((View) parent).findViewById(actionSlotId);
            }
            if (actionKey == null || !(actionKey.getParent() instanceof LinearLayout)) {
                return;
            }
            LinearLayout row = (LinearLayout) actionKey.getParent();
            if (row.getOrientation() != LinearLayout.HORIZONTAL) {
                return;
            }
            if (key.getParent() == row
                    && row.indexOfChild(key) == row.indexOfChild(actionKey) - 1) {
                return;
            }
            ViewGroup.LayoutParams old = key.getLayoutParams();
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    key.getWidth(), old == null ? ViewGroup.LayoutParams.MATCH_PARENT : old.height,
                    0f);
            if (old instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) old;
                params.setMargins(margins.leftMargin, margins.topMargin,
                        margins.rightMargin, margins.bottomMargin);
            }
            ((ViewGroup) key.getParent()).removeView(key);
            row.addView(key, row.indexOfChild(actionKey), params);
        }
    }
}
