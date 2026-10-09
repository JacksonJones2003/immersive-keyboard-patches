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

/**
 * InputView.onDraw, which paints a solid strip behind the system navigation bar on Android 16+.
 */
object InputViewOnDrawFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/inputmethod/inputview/InputView;",
    name = "onDraw",
    returnType = "V",
    parameters = listOf("Landroid/graphics/Canvas;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/graphics/Paint;",
            name = "setColor",
        )
    )
)

/**
 * Applies the navigation bar color Gboard computed from the keyboard theme to the IME window.
 */
object ApplyNavigationBarColorFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/Window;", "L"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/view/Window;",
            name = "getNavigationBarColor",
        ),
        methodCall(
            definingClass = "Landroid/view/Window;",
            name = "setNavigationBarColor",
        )
    )
)

/**
 * InputView.dispatchDraw, which draws the whole keyboard through its super call.
 */
object InputViewDispatchDrawFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/inputmethod/inputview/InputView;",
    name = "dispatchDraw",
    returnType = "V",
    parameters = listOf("Landroid/graphics/Canvas;"),
    filters = listOf(
        methodCall(name = "dispatchDraw")
    )
)
