package dev.blamspot.jcode.core.browserext

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * One bundled extension as its shipped `manifest.json` describes it.
 *
 * The manifest is the source of truth for the name/version/pages an extension reports, so the
 * catalog reads it out of the APK instead of duplicating those values in Kotlin — a version bump in
 * the extension is then one edit, in the file that already declares it. [extension] stays the entry
 * the browser runtime activates; the remaining fields are what a manager UI needs to show it.
 */
data class BundledExtensionDetails(
    val extension: BrowserExtension,
    val description: String,
    /** The 128px toolbar icon, as an APK asset path (`null` when the manifest declares none). */
    val iconAsset: String?,
    /** The toolbar popup page, as an APK asset path. */
    val actionPage: String?,
    /** The side-panel page, as an APK asset path. */
    val sidePanelPage: String?,
    /** Host patterns the extension's content scripts are declared for. */
    val contentScriptMatches: List<String>,
    /** Skill ids shipped under `skills/`, each with a `SKILL.md`. */
    val skills: List<String>,
) {
    val id: String get() = extension.id
    val name: String get() = extension.name
    val version: String get() = extension.version
}

/**
 * Reads the extensions shipped inside the APK.
 *
 * Asset-backed and read on demand: nothing is cached, because the only bundled-extension state that
 * changes at runtime is the enable flag, which lives in [BundledExtensionPreferences]. A manifest
 * that cannot be read yields the declared entry with empty details rather than hiding the extension
 * — a broken manifest should be visible, not silently absent.
 */
class BundledExtensionCatalog(private val assets: AssetManager) {

    fun all(): List<BundledExtensionDetails> = BundledBrowserExtensions.all.map(::load)

    fun load(extension: BrowserExtension): BundledExtensionDetails {
        val dir = extension.assetDir.trimEnd('/')
        val manifest = readManifest("$dir/manifest.json")
        return BundledExtensionDetails(
            extension = extension.copy(
                name = manifest?.optString("name")?.takeIf { it.isNotBlank() } ?: extension.name,
                version = manifest?.optString("version").orEmpty(),
            ),
            description = manifest?.optString("description").orEmpty(),
            iconAsset = manifest?.optJSONObject("icons")
                ?.optString("128")
                ?.takeIf { it.isNotBlank() }
                ?.let { "$dir/$it" },
            actionPage = manifest?.optJSONObject("action")
                ?.optString("default_popup")
                ?.takeIf { it.isNotBlank() }
                ?.let { "$dir/$it" },
            sidePanelPage = manifest?.optJSONObject("side_panel")
                ?.optString("default_path")
                ?.takeIf { it.isNotBlank() }
                ?.let { "$dir/$it" },
            contentScriptMatches = contentScriptMatches(manifest),
            skills = listSkills(dir),
        )
    }

    private fun contentScriptMatches(manifest: JSONObject?): List<String> {
        val scripts = manifest?.optJSONArray("content_scripts") ?: return emptyList()
        return buildList {
            for (index in 0 until scripts.length()) {
                val matches = scripts.optJSONObject(index)?.optJSONArray("matches") ?: continue
                for (matchIndex in 0 until matches.length()) {
                    val match = matches.optString(matchIndex).takeIf { it.isNotBlank() } ?: continue
                    if (match !in this) add(match)
                }
            }
        }
    }

    private fun listSkills(dir: String): List<String> =
        assets.list("$dir/skills").orEmpty()
            .filter { hasAsset("$dir/skills/$it/SKILL.md") }
            .sorted()

    private fun hasAsset(path: String): Boolean =
        runCatching { assets.open(path).close() }.isSuccess

    private fun readManifest(path: String): JSONObject? = runCatching {
        assets.open(path).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()
}
