package app.immersivekeyboard.patches.gboard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Static initializer that declares Gboard's display cutout flags, in this order and all
 * defaulting to false through one shared register:
 * enable_display_cutout_customization, config_ignore_display_cutout_area,
 * auto_edge_to_edge_keyboard.
 */
object DisplayCutoutFlagsFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("enable_display_cutout_customization"),
        opcode(Opcode.INVOKE_STATIC),
        string("config_ignore_display_cutout_area"),
        string("auto_edge_to_edge_keyboard"),
    )
)

/**
 * Any method of DisplayCutoutModule. Used only to find the class.
 */
object DisplayCutoutModuleFingerprint : Fingerprint(
    strings = listOf("com/google/android/libraries/inputmethod/displaycutout/DisplayCutoutModule")
)

/**
 * DisplayCutoutModule's check for whether the keyboard window should ignore the cutout.
 * Its result is passed to the method that sets layoutInDisplayCutoutMode to ALWAYS or DEFAULT.
 */
object ShouldIgnoreDisplayCutoutFingerprint : Fingerprint(
    classFingerprint = DisplayCutoutModuleFingerprint,
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/text/TextUtils;",
            name = "isEmpty",
        )
    )
)
