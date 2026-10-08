package app.immersivekeyboard.patches.gboard

import app.immersivekeyboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch

private const val LAYOUT_PARAMS = "Landroid/view/WindowManager\$LayoutParams;"

@Suppress("unused")
val immersiveKeyboardPatch = bytecodePatch(
    name = "Immersive Keyboard",
    description = "Extends the keyboard under the camera cutout in landscape " +
        "instead of leaving an empty strip beside it.",
    default = true
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    execute {
        // Android letterboxes the keyboard window beside the cutout unless the window opts in.
        // v0 and v1 are free at method entry. 0x1e = Android 11, where
        // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS (0x3) was added.
        val method = OnConfigureWindowFingerprint.method
        val registers = method.implementation?.registerCount
            ?: error("onConfigureWindow has no implementation")
        // this + Window + two booleans are the last four registers.
        check(registers >= 6) { "onConfigureWindow has no free registers: $registers" }

        method.addInstructionsWithLabels(
            0,
            """
                sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                const/16 v1, 0x1e
                if-lt v0, v1, :skip_cutout
                invoke-virtual { p1 }, Landroid/view/Window;->getAttributes()$LAYOUT_PARAMS
                move-result-object v0
                const/4 v1, 0x3
                iput v1, v0, $LAYOUT_PARAMS->layoutInDisplayCutoutMode:I
                invoke-virtual { p1, v0 }, Landroid/view/Window;->setAttributes($LAYOUT_PARAMS)V
                :skip_cutout
                nop
            """
        )
    }
}
