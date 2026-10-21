package app.immersivekeyboard.patches.gboard

import app.immersivekeyboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS = "Lapp/immersivekeyboard/extension/PressEffect;"

@Suppress("unused")
val glassPressEffectPatch = bytecodePatch(
    name = "Glass Press Effect",
    description = "Adds vivo-style liquid glass touch feedback: a soft light blooms out from " +
        "your finger across the keyboard background, and the keys dip slightly and " +
        "springs back with a small wobble.",
    default = true
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    extendWith("extensions/extension.mpe")

    execute {
        InputViewDispatchTouchFingerprint.method.addInstructions(
            0,
            "invoke-static { p0, p1 }, " +
                "$EXTENSION_CLASS->onTouch(Landroid/view/View;Landroid/view/MotionEvent;)V"
        )
        // After the keyboard has drawn. The glass blur clears whatever is painted beneath it,
        // so the light has to go on top. The parameter registers are reused right after the
        // super call, so hook directly behind it.
        InputViewDispatchDrawFingerprint.let {
            it.method.addInstructions(
                it.instructionMatches.first().index + 1,
                "invoke-static { p0, p1 }, " +
                    "$EXTENSION_CLASS->draw(Landroid/view/View;Landroid/graphics/Canvas;)V"
            )
        }
    }
}
