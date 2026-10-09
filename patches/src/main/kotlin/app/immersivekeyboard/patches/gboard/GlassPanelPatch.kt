package app.immersivekeyboard.patches.gboard

import app.immersivekeyboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS = "Lapp/immersivekeyboard/extension/GlassPanel;"

@Suppress("unused")
val glassPanelPatch = bytecodePatch(
    name = "Glass Panel",
    description = "Gives the translucent keyboard panel a glass look with lit edges, a faint " +
        "sheen and a bright top rim, leaving the keys as they are. Works on top of " +
        "Translucent Bottom Bar and Frosted Glass.",
    default = true
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    // The effect is drawn from the bottom bar's drawing hooks.
    dependsOn(translucentBottomBarPatch)

    extendWith("extensions/extension.mpe")

    execute {
        // Switches the effect on. The parameter registers are reused right after the super
        // call, so hook directly behind it.
        InputViewDispatchDrawFingerprint.let {
            it.method.addInstructions(
                it.instructionMatches.first().index + 1,
                "invoke-static { }, $EXTENSION_CLASS->enable()V"
            )
        }
    }
}
