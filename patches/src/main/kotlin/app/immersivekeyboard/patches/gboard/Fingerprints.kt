package app.immersivekeyboard.patches.gboard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

/**
 * Gboard's override of InputMethodService.onConfigureWindow(Window, boolean, boolean).
 *
 * Framework overrides keep their names, so this does not depend on obfuscated class names.
 */
object OnConfigureWindowFingerprint : Fingerprint(
    name = "onConfigureWindow",
    returnType = "V",
    parameters = listOf("Landroid/view/Window;", "Z", "Z"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/view/Window;",
            name = "setLayout",
        )
    )
)
