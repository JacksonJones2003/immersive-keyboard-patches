package app.immersivekeyboard.patches.gboard

import app.immersivekeyboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val SET_WALLPAPER = "android.permission.SET_WALLPAPER"
private const val VIVO_THEME_RESOURCES = "com.bbk.theme.resources"

/**
 * Lets Gboard set the phone's wallpaper, for the wallpaper that follows dark mode, and see
 * vivo's theme resources, where vivo's own wallpapers come from.
 */
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

            val application = manifest.getElementsByTagName("application").item(0)
            val queries = manifest.getElementsByTagName("queries").let {
                if (it.length > 0) it.item(0) as Element
                else document.createElement("queries").also { created ->
                    manifest.insertBefore(created, application)
                }
            }
            val packages = queries.getElementsByTagName("package")
            val listed = (0 until packages.length).any {
                (packages.item(it) as Element).getAttributeNS(ANDROID_NS, "name") ==
                    VIVO_THEME_RESOURCES
            }
            if (!listed) {
                queries.appendChild(document.createElement("package").apply {
                    setAttributeNS(ANDROID_NS, "android:name", VIVO_THEME_RESOURCES)
                })
            }
        }
    }
}
