package app.immersivekeyboard.patches.gboard

import app.immersivekeyboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS = "Lapp/immersivekeyboard/extension/ThemeDarkMode;"

@Suppress("unused")
val themeFollowsDarkModePatch = bytecodePatch(
    name = "Theme Follows Dark Mode",
    description = "Gives the keyboard a light theme and a dark theme. Pick a theme while the " +
        "phone is in light mode and another while it is in dark mode, and the keyboard " +
        "switches between them when the system dark mode changes.",
    default = true
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    extendWith("extensions/extension.mpe")

    execute {
        // The parameter registers are reused right after the super call, so hook directly
        // behind it.
        InputViewDispatchDrawFingerprint.let {
            it.method.addInstructions(
                it.instructionMatches.first().index + 1,
                "invoke-static { p0 }, $EXTENSION_CLASS->check(Landroid/view/View;)V"
            )
        }
    }
}
