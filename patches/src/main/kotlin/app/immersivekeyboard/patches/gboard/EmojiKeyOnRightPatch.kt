package app.immersivekeyboard.patches.gboard

import app.immersivekeyboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS = "Lapp/immersivekeyboard/extension/EmojiKeyPosition;"

@Suppress("unused")
val emojiKeyOnRightPatch = bytecodePatch(
    name = "Emoji Key On Right",
    description = "Moves the dedicated emoji key from the left of the spacebar to just left of " +
        "the enter / search key.",
    default = true
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    extendWith("extensions/extension.mpe")

    execute {
        // Gboard's bottom row layouts put this key before the spacebar. Reorder it at runtime
        // once a keyboard has been laid out. The parameter registers are reused right after
        // the super call, so hook directly behind it.
        InputViewDispatchDrawFingerprint.let {
            it.method.addInstructions(
                it.instructionMatches.first().index + 1,
                "invoke-static { p0 }, $EXTENSION_CLASS->attach(Landroid/view/View;)V"
            )
        }
    }
}
