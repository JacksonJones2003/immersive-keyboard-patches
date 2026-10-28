package app.immersivekeyboard.patches.gboard

import app.immersivekeyboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val SET_WALLPAPER = "android.permission.SET_WALLPAPER"

/** Lets Gboard set the phone's wallpaper, for the wallpaper that follows dark mode. */
internal val wallpaperPermissionPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_GBOARD)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val permissions = manifest.getElementsByTagName("uses-permission")
            val present = (0 until permissions.length).any {
                (permissions.item(it) as Element).getAttributeNS(ANDROID_NS, "name") ==
                    SET_WALLPAPER
            }
            if (!present) {
                val permission = document.createElement("uses-permission")
                permission.setAttributeNS(ANDROID_NS, "android:name", SET_WALLPAPER)
                val application = manifest.getElementsByTagName("application").item(0)
                manifest.insertBefore(permission, application)
            }
        }
    }
}
