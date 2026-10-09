package app.immersivekeyboard.extension;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Moves the dedicated emoji key (the slot Gboard shares with the language switch key) from
 * the left of the spacebar to its right.
 */
@SuppressWarnings("unused")
public final class EmojiKeyPosition {
    // Layout slot names from Gboard's bottom row layouts.
    private static final String EMOJI_KEY_SLOT = "key_pos_switch_to_next_language";
    private static final String SPACE_KEY_SLOT = "key_pos_space";

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
        private int spaceSlotId;

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
                    moveRightOfSpace(key);
                }
            } catch (Throwable ignored) {
                // Leave the keyboard as Gboard laid it out.
            }
        }

        private boolean resolveSlots(View view) {
            if (emojiSlotId != 0 && spaceSlotId != 0) {
                return true;
            }
            Resources resources = view.getResources();
            // The resource package keeps its original name even when the app is renamed.
            String resourcePackage = view.getId() != View.NO_ID
                    ? resources.getResourcePackageName(view.getId())
                    : view.getContext().getPackageName();
            emojiSlotId = resources.getIdentifier(EMOJI_KEY_SLOT, "id", resourcePackage);
            spaceSlotId = resources.getIdentifier(SPACE_KEY_SLOT, "id", resourcePackage);
            return emojiSlotId != 0 && spaceSlotId != 0;
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
         * The key sits in a horizontal row next to the spacebar, or next to a group holding
         * the spacebar. If it comes before that sibling, move it to the end of the row.
         */
        private void moveRightOfSpace(View key) {
            if (!(key.getParent() instanceof LinearLayout)) {
                return;
            }
            LinearLayout row = (LinearLayout) key.getParent();
            if (row.getOrientation() != LinearLayout.HORIZONTAL) {
                return;
            }
            int keyIndex = row.indexOfChild(key);
            int spaceIndex = -1;
            for (int index = 0; index < row.getChildCount(); index++) {
                View sibling = row.getChildAt(index);
                if (sibling != key && sibling.findViewById(spaceSlotId) != null) {
                    spaceIndex = index;
                    break;
                }
            }
            if (spaceIndex < 0 || keyIndex > spaceIndex) {
                return;
            }
            ViewGroup.LayoutParams params = key.getLayoutParams();
            row.removeView(key);
            row.addView(key, params);
        }
    }
}
