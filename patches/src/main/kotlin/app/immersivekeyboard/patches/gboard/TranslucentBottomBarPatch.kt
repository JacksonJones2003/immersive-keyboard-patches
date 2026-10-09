package app.immersivekeyboard.patches.gboard

import app.immersivekeyboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/immersivekeyboard/extension/BottomBarOpacity;"

@Suppress("unused")
val translucentBottomBarPatch = bytecodePatch(
    name = "Translucent Bottom Bar",
    description = "Makes the bar under the keyboard (behind the globe and close buttons) match " +
        "the keyboard's color, opacity and blur, for use with transparent themes such as " +
        "Frosted Glass.",
    default = true
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    extendWith("extensions/extension.mpe")

    execute {
        // Before Android 16 the strip is the window's navigation bar color. The same value is
        // also handed to InputView, so scale it once here, before it is read.
        ApplyNavigationBarColorFingerprint.let {
            val readCurrentColor = it.instructionMatches[0].index
            val colorRegister = it.method.getInstruction<FiveRegisterInstruction>(
                it.instructionMatches[1].index
            ).registerD
            it.method.addInstructions(
                readCurrentColor,
                """
                    invoke-static { p1, v$colorRegister }, $EXTENSION_CLASS->navigationBarColor(Landroid/view/Window;I)I
                    move-result v$colorRegister
                """
            )
        }

        // On Android 16+ Gboard paints this strip itself, in an opaque theme color, in the
        // bottom padding of InputView. Replace that color with the keyboard's own color and
        // opacity, and blur behind the strip when Frosted Glass is on.
        InputViewOnDrawFingerprint.let {
            val setColor = it.instructionMatches.first().index
            val colorRegister =
                it.method.getInstruction<FiveRegisterInstruction>(setColor).registerD
            it.method.addInstructions(
                setColor,
                """
                    invoke-static { p0, p1, v$colorRegister }, $EXTENSION_CLASS->stripColor(Landroid/view/View;Landroid/graphics/Canvas;I)I
                    move-result v$colorRegister
                """
            )
        }

        // Once the keyboard has drawn, fix up its bottom corners where they meet the strip.
        // The parameter registers are reused right after the super call, so hook directly
        // behind it.
        InputViewDispatchDrawFingerprint.let {
            it.method.addInstructions(
                it.instructionMatches.first().index + 1,
                "invoke-static { p0, p1 }, " +
                    "$EXTENSION_CLASS->afterKeyboardDrawn(Landroid/view/View;Landroid/graphics/Canvas;)V"
            )
        }
    }
}
