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
    description = "Makes the bar under the keyboard (behind the globe and close buttons) follow " +
        "the keyboard's opacity, for use with transparent themes such as Frosted Glass.",
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
        // bottom padding of InputView. Scale that color by the keyboard surfaces' alpha.
        InputViewOnDrawFingerprint.let {
            val setColor = it.instructionMatches.first().index
            val colorRegister =
                it.method.getInstruction<FiveRegisterInstruction>(setColor).registerD
            it.method.addInstructions(
                setColor,
                """
                    invoke-static { p0, v$colorRegister }, $EXTENSION_CLASS->stripColor(Landroid/view/View;I)I
                    move-result v$colorRegister
                """
            )
        }
    }
}
