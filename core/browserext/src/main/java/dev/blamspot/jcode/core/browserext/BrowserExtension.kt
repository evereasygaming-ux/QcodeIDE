package dev.blamspot.jcode.core.browserext

/** Where an extension came from. A user-installed copy shadows the bundled one with the same id. */
enum class BrowserExtensionSource { BUNDLED, USER }

/** One Chromium extension the in-app browser can load. [assetDir] is relative to the APK assets. */
data class BrowserExtension(
    val id: String,
    val name: String,
    val version: String,
    val assetDir: String,
    val source: BrowserExtensionSource,
)

/** Extensions shipped inside the APK. DeepX is the first bundled extension. */
object BundledBrowserExtensions {
    const val DEEPX_FLOW_ID = "deepx-flow"
    const val DEEPX_FLOW_DIR = "extensions/deepx-flow"

    val deepxFlow = BrowserExtension(
        id = DEEPX_FLOW_ID,
        name = "DeepX Flow",
        version = "",
        assetDir = DEEPX_FLOW_DIR,
        source = BrowserExtensionSource.BUNDLED,
    )

    val all: List<BrowserExtension> = listOf(deepxFlow)
}
