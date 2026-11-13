package dev.blamspot.jcode.core.browserext

import android.content.Context

/**
 * Whether each bundled extension is enabled.
 *
 * The assets always ship inside the APK; this is the user's switch. Kept in shared preferences
 * rather than next to the assets so reinstalling or updating the APK cannot reset it.
 */
class BundledExtensionPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(extensionId: String): Boolean = prefs.getBoolean(key(extensionId), true)

    fun setEnabled(extensionId: String, enabled: Boolean) {
        prefs.edit().putBoolean(key(extensionId), enabled).apply()
    }

    private fun key(extensionId: String) = "enabled:$extensionId"

    private companion object {
        const val FILE = "bundled_extensions"
    }
}
