package app.immersivekeyboard.extension;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * Gives the translucent keyboard panel a glass look: light caught along its edges, a faint
 * sheen across it and a bright rim on top. An app cannot bend the pixels behind its window,
 * so the edge "refraction" is imitated with light and dark bands; keys are left untouched.
 */
@SuppressWarnings("unused")
public final class GlassPanel {
    private static final float EDGE_GLOW_DP = 14f;
    private static final float EDGE_SHADE_DP = 26f;
    private static final float SIDE_GLOW_DP = 9f;
    private static final float RIM_DP = 1.25f;

    private static volatile boolean enabled;

    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static float cachedLeft = -1, cachedTop = -1, cachedRight = -1, cachedBottom = -1;
    private static Shader sheen, topGlow, topShade, bottomGlow, leftGlow, rightGlow, rim;

    private GlassPanel() {
    }

    /** Injection point. Present only when the Glass Panel patch is applied. */
    public static void enable() {
        enabled = true;
    }

    static boolean isEnabled() {
        return enabled;
    }

    /**
     * Drawn underneath the keyboard, so the panel's own tint softens it and keys cover it.
     * The rectangle is the whole panel including the bottom bar.
     */
    static void drawBehind(Canvas canvas, float left, float top, float right, float bottom,
            float density) {
        if (!enabled || bottom - top <= 0 || right - left <= 0) {
            return;
        }
        prepare(left, top, right, bottom, density);
        float glow = EDGE_GLOW_DP * density;
        float shade = EDGE_SHADE_DP * density;
        float side = SIDE_GLOW_DP * density;

        fill(canvas, sheen, left, top, right, bottom);
        // Light gathered at the top edge, then the darker band a thick glass edge leaves.
        fill(canvas, topGlow, left, top, right, top + glow);
        fill(canvas, topShade, left, top, right, top + shade);
        fill(canvas, bottomGlow, left, bottom - glow, right, bottom);
        fill(canvas, leftGlow, left, top, left + side, bottom);
        fill(canvas, rightGlow, right - side, top, right, bottom);
    }

    /** Drawn over the finished keyboard: the thin bright rim along the panel's top edge. */
    static void drawOnTop(Canvas canvas, float left, float top, float right, float density) {
        if (!enabled || right - left <= 0) {
            return;
        }
        float thickness = RIM_DP * density;
        if (rim == null) {
            return;
        }
        fill(canvas, rim, left, top, right, top + thickness);
    }

    private static void fill(Canvas canvas, Shader shader, float left, float top, float right,
            float bottom) {
        PAINT.setShader(shader);
        canvas.drawRect(left, top, right, bottom, PAINT);
        PAINT.setShader(null);
    }

    private static void prepare(float left, float top, float right, float bottom,
            float density) {
        if (left == cachedLeft && top == cachedTop && right == cachedRight
                && bottom == cachedBottom && sheen != null) {
            return;
        }
        cachedLeft = left;
        cachedTop = top;
        cachedRight = right;
        cachedBottom = bottom;
        float glow = EDGE_GLOW_DP * density;
        float shade = EDGE_SHADE_DP * density;
        float side = SIDE_GLOW_DP * density;
        int clear = Color.TRANSPARENT;

        sheen = new LinearGradient(left, top, right, bottom,
                new int[]{white(34), clear, clear, white(14)},
                new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        topGlow = new LinearGradient(0, top, 0, top + glow,
                white(92), clear, Shader.TileMode.CLAMP);
        topShade = new LinearGradient(0, top, 0, top + shade,
                new int[]{clear, black(30), clear},
                new float[]{0.3f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        bottomGlow = new LinearGradient(0, bottom - glow, 0, bottom,
                clear, white(44), Shader.TileMode.CLAMP);
        leftGlow = new LinearGradient(left, 0, left + side, 0,
                white(56), clear, Shader.TileMode.CLAMP);
        rightGlow = new LinearGradient(right - side, 0, right, 0,
                clear, white(56), Shader.TileMode.CLAMP);
        // Brightest toward the corners, the way a curved glass edge catches light.
        rim = new LinearGradient(left, 0, right, 0,
                new int[]{white(210), white(105), white(210)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    private static int white(int alpha) {
        return Color.argb(alpha, 255, 255, 255);
    }

    private static int black(int alpha) {
        return Color.argb(alpha, 0, 0, 0);
    }
}
