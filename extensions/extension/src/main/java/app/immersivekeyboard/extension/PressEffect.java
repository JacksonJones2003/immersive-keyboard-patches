package app.immersivekeyboard.extension;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Touch feedback in the style of vivo's liquid glass controls: a soft light blooms out from
 * the finger across the glass, and the keys dip slightly under the finger and spring back with
 * a small wobble when it is released. The glass sheet itself never moves: the system draws its
 * blur separately and a frame behind, so moving the sheet made its corners glitch while typing.
 */
@SuppressWarnings("unused")
public final class PressEffect {
    private static final long FLASH_RISE_MS = 70;
    private static final long FLASH_FADE_MS = 380;
    private static final int FLASH_PEAK_ALPHA = 56;
    private static final float FLASH_RADIUS_FRACTION = 0.55f;
    private static final float PRESSED_SCALE = 0.992f;
    private static final long PRESS_MS = 90;
    private static final long SPRING_MS = 420;

    private static final String KEYBOARD_VIEW_CLASS = "SoftKeyboardView";

    private static final Map<View, State> STATES = new WeakHashMap<>();

    private PressEffect() {
    }

    /** Injection point. Called first thing in InputView.dispatchTouchEvent. */
    public static void onTouch(View inputView, MotionEvent event) {
        try {
            State state = STATES.get(inputView);
            if (state == null) {
                state = new State();
                STATES.put(inputView, state);
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                int index = event.getActionIndex();
                state.flash(inputView, event.getX(index), event.getY(index));
                state.press(inputView);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                state.release(inputView);
            }
        } catch (Throwable ignored) {
            // Feedback must never get in the way of typing.
        }
    }

    /**
     * Injection point. Called from InputView.dispatchDraw once the keyboard has drawn. The glass
     * blur clears anything painted beneath it, so the light is laid over the finished keyboard.
     */
    public static void draw(View inputView, Canvas canvas) {
        try {
            State state = STATES.get(inputView);
            if (state != null) {
                state.drawFlash(inputView, canvas);
            }
        } catch (Throwable ignored) {
            // Never block drawing.
        }
    }

    private static final class State {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path sheet = new Path();
        private float flashX;
        private float flashY;
        private long flashStart = -1;
        private ValueAnimator scale;

        void flash(View view, float x, float y) {
            flashX = x;
            flashY = y;
            flashStart = SystemClock.uptimeMillis();
            view.invalidate();
        }

        void drawFlash(View view, Canvas canvas) {
            if (flashStart < 0) {
                return;
            }
            long elapsed = SystemClock.uptimeMillis() - flashStart;
            float strength;
            if (elapsed < FLASH_RISE_MS) {
                strength = elapsed / (float) FLASH_RISE_MS;
            } else if (elapsed < FLASH_RISE_MS + FLASH_FADE_MS) {
                float fade = 1f - (elapsed - FLASH_RISE_MS) / (float) FLASH_FADE_MS;
                strength = fade * fade;
            } else {
                flashStart = -1;
                return;
            }
            // The light spreads outward a little as it fades.
            float spread = 0.75f + 0.25f * Math.min(1f, elapsed / (float) (FLASH_RISE_MS + FLASH_FADE_MS));
            float radius = Math.max(1f, view.getWidth() * FLASH_RADIUS_FRACTION * spread);
            int alpha = Math.round(FLASH_PEAK_ALPHA * strength);
            paint.setShader(new RadialGradient(flashX, flashY, radius,
                    new int[]{Color.argb(alpha, 255, 255, 255),
                            Color.argb(alpha / 3, 255, 255, 255), Color.TRANSPARENT},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            // Only light the glass itself, never the cleared corners or the space above it.
            int save = canvas.save();
            if (BottomBarOpacity.glassSheet(view, sheet)) {
                canvas.clipPath(sheet);
            }
            canvas.drawRect(0, 0, view.getWidth(), view.getHeight(), paint);
            canvas.restoreToCount(save);
            paint.setShader(null);
            view.postInvalidateOnAnimation();
        }

        void press(View view) {
            animateScale(keyPanels(view), PRESSED_SCALE, PRESS_MS, null);
        }

        void release(View view) {
            animateScale(keyPanels(view), 1f, SPRING_MS, SPRING);
        }

        /**
         * The rows of keys inside each keyboard panel. The panels themselves carry the theme's
         * tint, so scaling them would uncover a sliver of untinted glass along their edges;
         * scaling only what is inside them leaves every background where it is.
         */
        private List<View> keyPanels(View inputView) {
            List<View> panels = new ArrayList<>();
            collectKeyPanels(inputView, inputView.getWidth() * 0.8f, panels);
            List<View> contents = new ArrayList<>();
            for (View panel : panels) {
                if (!(panel instanceof ViewGroup)) {
                    continue;
                }
                ViewGroup group = (ViewGroup) panel;
                for (int index = 0; index < group.getChildCount(); index++) {
                    View child = group.getChildAt(index);
                    if (child.getVisibility() == View.VISIBLE) {
                        // Pivot on the panel's bottom centre so the rows move as one piece.
                        child.setPivotX(panel.getWidth() / 2f - child.getLeft());
                        child.setPivotY(panel.getHeight() - child.getTop());
                        contents.add(child);
                    }
                }
            }
            return contents;
        }

        private static void collectKeyPanels(View view, float minWidth, List<View> out) {
            if (view.getVisibility() != View.VISIBLE) {
                return;
            }
            if (view.getClass().getName().endsWith(KEYBOARD_VIEW_CLASS)
                    && view.getWidth() >= minWidth) {
                out.add(view);
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    collectKeyPanels(group.getChildAt(index), minWidth, out);
                }
            }
        }

        private void animateScale(List<View> views, float target, long duration,
                Interpolator interpolator) {
            if (scale != null) {
                scale.cancel();
            }
            if (views.isEmpty()) {
                return;
            }
            float[] start = new float[views.size()];
            for (int index = 0; index < views.size(); index++) {
                start[index] = views.get(index).getScaleX();
            }
            scale = ValueAnimator.ofFloat(0f, 1f);
            scale.setDuration(duration);
            if (interpolator != null) {
                scale.setInterpolator(interpolator);
            }
            scale.addUpdateListener(animation -> {
                float progress = (float) animation.getAnimatedValue();
                for (int index = 0; index < views.size(); index++) {
                    float value = start[index] + (target - start[index]) * progress;
                    views.get(index).setScaleX(value);
                    views.get(index).setScaleY(value);
                }
            });
            scale.start();
        }
    }

    /** A lightly damped spring: overshoots once or twice, then settles. */
    private static final Interpolator SPRING = t ->
            (float) (1 - Math.exp(-8.5 * t) * Math.cos(13 * t));
}
