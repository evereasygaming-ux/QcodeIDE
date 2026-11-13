package dev.blamspot.jcode

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.blamspot.jcode.core.browserext.BundledBrowserExtensions
import dev.blamspot.jcode.core.browserext.BundledExtensionPreferences
import dev.blamspot.jcode.core.browserext.DeepXBridgeServer
import dev.blamspot.jcode.core.browserext.DeepXSurface
import dev.blamspot.jcode.core.distro.DistroService
import dev.blamspot.jcode.core.distro.WorkspaceHostPaths
import dev.blamspot.jcode.workbench.BuiltinBrowser
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DeepXRuntime(
    private val context: Context,
    private val distroService: DistroService,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Waiting : State
        data object Starting : State
        data class Running(val port: Int, val scriptPath: String) : State
        data class Failed(val message: String) : State
    }

    private val prefs = context.getSharedPreferences("deepx_bridge", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val bridge = DeepXBridgeServer(BuiltinBrowserDeepXSurface())
    private val _state = MutableStateFlow<State>(State.Waiting)
    val state: StateFlow<State> = _state.asStateFlow()

    private var observing = false

    fun start() {
        if (observing) return
        observing = true
        scope.launch {
            distroService.environmentState
                .map {
                    Triple(
                        it.distroInstalled == true && it.jcodeUserReady == true,
                        it.runtime.selectedDistro.id,
                        it.runtime.user,
                    )
                }
                .distinctUntilChanged()
                .collect { (ready, _, _) ->
                    // The enable switch is a real gate, not a badge: disabling the extension stops
                    // the bridge now, and re-enabling starts it without a shell restart.
                    if (!bundledExtensionEnabled()) {
                        halt()
                    } else if (ready) {
                        ensureStarted()
                    } else {
                        _state.value = State.Waiting
                    }
                }
        }
    }

    fun stop() {
        halt()
        observing = false
    }

    /** Persist the bundled extension's enable switch and start/stop the bridge to match. */
    fun setBundledExtensionEnabled(enabled: Boolean) {
        BundledExtensionPreferences(context).setEnabled(BundledBrowserExtensions.DEEPX_FLOW_ID, enabled)
        if (enabled) {
            if (observing) {
                scope.launch { ensureStarted() }
            } else {
                start()
            }
        } else {
            halt()
        }
    }

    private fun halt() {
        bridge.stop()
        _state.value = State.Waiting
        // `agy` reads the guest's `~/.gemini/config/mcp_config.json` for every session, whatever
        // servers QCode passes over ACP, so leaving the entry behind would hand a disabled
        // extension's tools to the agent anyway.
        scope.launch(Dispatchers.IO) { runCatching { removeMcpConfig() } }
    }

    private fun bundledExtensionEnabled(): Boolean =
        BundledExtensionPreferences(context).isEnabled(BundledBrowserExtensions.DEEPX_FLOW_ID)

    suspend fun ensureStarted(): Result<Int> = lock.withLock {
        withContext(Dispatchers.IO) {
            bridge.boundPort?.let { return@withContext Result.success(it) }
            if (!distroService.isRuntimeReady()) {
                _state.value = State.Waiting
                return@withContext Result.failure(IllegalStateException("Linux environment is not ready"))
            }
            _state.value = State.Starting
            val port = bridge.start().getOrElse {
                _state.value = State.Failed(it.message ?: "Could not start the DeepX bridge")
                return@withContext Result.failure(it)
            }
            install(port).fold(
                onSuccess = { scriptPath ->
                    _state.value = State.Running(port, scriptPath)
                    Result.success(port)
                },
                onFailure = {
                    bridge.stop()
                    _state.value = State.Failed(it.message ?: "Could not install the DeepX MCP server")
                    Result.failure(it)
                },
            )
        }
    }

    private fun install(port: Int): Result<String> {
        val user = distroService.environmentState.value.runtime.user
        val home = if (user == "root") "/root" else "/home/$user"
        val rootfs = distroService.activeRootfsPath()
        val dir = File(rootfs, home.trimStart('/') + "/.qcode/deepx")
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("Could not create ${dir.absolutePath}"))
        }
        dir.setReadable(true, false)
        dir.setExecutable(true, false)
        val script = File(dir, "deepx-lite-mcp.mjs")
        val updated = packageUpdateTime()
        if (!script.isFile || script.length() == 0L || prefs.getLong(KEY_INSTALLED, -1L) != updated) {
            runCatching {
                val temp = File(dir, "deepx-lite-mcp.mjs.tmp")
                context.assets.open(ASSET).use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp.setReadable(true, false)
                script.delete()
                if (!temp.renameTo(script)) {
                    temp.copyTo(script, overwrite = true)
                    temp.delete()
                }
                script.setReadable(true, false)
                prefs.edit().putLong(KEY_INSTALLED, updated).apply()
            }.getOrElse {
                return Result.failure(it)
            }
        }
        val scriptGuestPath = "$home/.qcode/deepx/${script.name}"
        runCatching {
            val portFile = File(dir, "bridge-port")
            portFile.writeText(port.toString())
            portFile.setReadable(true, false)
            writeMcpConfig(rootfs, home, scriptGuestPath)
        }.getOrElse {
            return Result.failure(it)
        }
        return Result.success(scriptGuestPath)
    }

    /**
     * Copy every skill bundled with the extension into the guest's agent skills directory.
     *
     * The same assets the extension ships, installed where `agy` discovers customizations
     * (`~/.gemini/config/skills/<id>/SKILL.md`). Idempotent: a second call rewrites the files, so a
     * newer APK updates skills without the user removing the old copies first.
     */
    suspend fun installBundledSkills(): Result<Int> = withContext(Dispatchers.IO) {
        if (!distroService.isRuntimeReady()) {
            return@withContext Result.failure(IllegalStateException("Linux environment is not ready"))
        }
        val user = distroService.environmentState.value.runtime.user
        val home = if (user == "root") "/root" else "/home/$user"
        val rootfs = distroService.activeRootfsPath()
        val skillsRoot = File(rootfs, home.trimStart('/') + "/.gemini/config/skills")
        runCatching {
            val ids = context.assets.list(ASSET_SKILLS).orEmpty()
                .filter { id -> assetExists("$ASSET_SKILLS/$id/SKILL.md") }
            if (ids.isEmpty()) {
                throw IllegalStateException("No bundled skills found in $ASSET_SKILLS")
            }
            if (!skillsRoot.isDirectory && !skillsRoot.mkdirs()) {
                throw IllegalStateException("Could not create ${skillsRoot.absolutePath}")
            }
            skillsRoot.setReadable(true, false)
            skillsRoot.setExecutable(true, false)
            ids.forEach { id ->
                val dir = File(skillsRoot, id)
                if (!dir.isDirectory && !dir.mkdirs()) {
                    throw IllegalStateException("Could not create ${dir.absolutePath}")
                }
                dir.setReadable(true, false)
                dir.setExecutable(true, false)
                copyAsset("$ASSET_SKILLS/$id/SKILL.md", File(dir, "SKILL.md"))
            }
            // The extension's own installer drops its rules file beside the skills
            // (`cp -r skills/*`), and agy reads it from the same config tree.
            if (assetExists("$ASSET_SKILLS/GEMINI.md")) {
                copyAsset("$ASSET_SKILLS/GEMINI.md", File(skillsRoot, "GEMINI.md"))
            }
            ids.size
        }
    }

    private fun assetExists(path: String): Boolean =
        runCatching { context.assets.open(path).close() }.isSuccess

    /**
     * Extract the bundled video tool into the guest's `/workspace/.qcode-tools/video`.
     *
     * The tree ships as one compressed asset because most of its 56 MB is the static ffmpeg binary
     * the guest has no system copy of. Extraction is versioned: the marker stops a second tap from
     * rewriting 56 MB, while a new bundled version replaces the code and leaves the user's `.env`
     * alone (it is not in the archive).
     */
    suspend fun installBundledVideoTool(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val destination = File(WorkspaceHostPaths.projectsRoot, VIDEO_TOOL_DIR)
            val marker = File(destination, VIDEO_TOOL_MARKER)
            val ffmpeg = File(destination, VIDEO_TOOL_FFMPEG)
            if (
                marker.isFile &&
                marker.readText().trim() == VIDEO_TOOL_VERSION &&
                ffmpeg.isFile &&
                File(destination, "qcode-video.mjs").isFile
            ) {
                return@runCatching destination
            }
            if (!destination.isDirectory && !destination.mkdirs()) {
                throw IllegalStateException("Could not create ${destination.absolutePath}")
            }
            val root = destination.canonicalFile
            val prefix = root.path + File.separator
            context.assets.open(ASSET_VIDEO_TOOL).use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // A zip entry is attacker-controlled text: resolve it, then prove the
                        // result stays under the tool directory before writing anything.
                        val target = File(root, entry.name).canonicalFile
                        if (target != root && !target.path.startsWith(prefix)) {
                            throw IllegalStateException(
                                "Refusing to extract ${entry.name} outside ${root.path}",
                            )
                        }
                        if (entry.isDirectory) {
                            if (!target.isDirectory && !target.mkdirs()) {
                                throw IllegalStateException("Could not create ${target.absolutePath}")
                            }
                        } else {
                            val parent = target.parentFile
                            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                                throw IllegalStateException("Could not create ${parent.absolutePath}")
                            }
                            target.outputStream().use { output -> zip.copyTo(output) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            // The archive carries no POSIX modes, and the pipeline spawns ffmpeg directly.
            ffmpeg.setExecutable(true, false)
            listOf(
                "list-voices.sh",
                "run-hindi.sh",
                "run-test.sh",
                "run-tutorial.sh",
                "test-skill.sh",
            ).forEach { File(destination, it).setExecutable(true, false) }
            marker.writeText(VIDEO_TOOL_VERSION)
            destination
        }
    }

    private fun copyAsset(asset: String, destination: File) {
        val temp = File(destination.parentFile, destination.name + ".tmp")
        context.assets.open(asset).use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        temp.setReadable(true, false)
        destination.delete()
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        destination.setReadable(true, false)
    }

    /**
     * Drop this extension's entry from the guest's agent MCP config.
     *
     * Only `deepx-flow` is touched: the file is the user's agent configuration and may hold other
     * servers, which a bundled extension has no business editing. A missing file or entry is a
     * no-op, so the disable path stays safe when the runtime never started.
     */
    private fun removeMcpConfig() {
        if (!distroService.isRuntimeReady()) return
        val user = distroService.environmentState.value.runtime.user
        val home = if (user == "root") "/root" else "/home/$user"
        val configFile = mcpConfigFile(distroService.activeRootfsPath(), home)
        if (!configFile.isFile) return
        val root = runCatching { JSONObject(configFile.readText()) }.getOrNull() ?: return
        val servers = root.optJSONObject("mcpServers") ?: return
        if (servers.remove("deepx-flow") == null) return
        val temp = File(configFile.parentFile, configFile.name + ".tmp")
        temp.writeText(root.toString(2))
        temp.setReadable(true, false)
        configFile.delete()
        if (!temp.renameTo(configFile)) {
            temp.copyTo(configFile, overwrite = true)
            temp.delete()
        }
        configFile.setReadable(true, false)
    }

    /** The MCP config `agy` reads for every session, inside the guest. */
    private fun mcpConfigFile(rootfs: File, home: String): File =
        File(rootfs, home.trimStart('/') + "/.gemini/config/mcp_config.json")

    private fun writeMcpConfig(rootfs: File, home: String, scriptGuestPath: String) {
        val configDir = File(rootfs, home.trimStart('/') + "/.gemini/config")
        if (!configDir.isDirectory && !configDir.mkdirs()) {
            throw IllegalStateException("Could not create ${configDir.absolutePath}")
        }
        configDir.setReadable(true, false)
        configDir.setExecutable(true, false)
        val configFile = mcpConfigFile(rootfs, home)
        val existing = if (configFile.isFile) configFile.readText() else ""
        val root = if (existing.isBlank()) {
            JSONObject()
        } else {
            runCatching { JSONObject(existing) }.getOrElse {
                throw IllegalStateException("Could not parse ${configFile.absolutePath}")
            }
        }
        val servers = root.optJSONObject("mcpServers") ?: JSONObject()
        servers.put(
            "deepx-flow",
            JSONObject()
                .put("command", "node")
                .put("args", JSONArray().put(scriptGuestPath)),
        )
        root.put("mcpServers", servers)
        val temp = File(configDir, "mcp_config.json.tmp")
        temp.writeText(root.toString(2))
        temp.setReadable(true, false)
        configFile.delete()
        if (!temp.renameTo(configFile)) {
            temp.copyTo(configFile, overwrite = true)
            temp.delete()
        }
        configFile.setReadable(true, false)
    }

    private fun packageUpdateTime(): Long = runCatching {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        ).lastUpdateTime
    }.getOrDefault(0L)

    private class BuiltinBrowserDeepXSurface : DeepXSurface {
        private val main = Handler(Looper.getMainLooper())

        override fun currentUrl(): String =
            onMain({ BuiltinBrowser.currentUrl.value }, "")

        override fun isAvailable(): Boolean =
            onMain({ BuiltinBrowser.controller != null }, false)

        override fun evaluate(script: String, onResult: (String?) -> Unit) {
            main.post {
                val controller = BuiltinBrowser.controller
                if (controller == null) onResult(null) else controller.eval(script) { onResult(it) }
            }
        }

        override fun navigate(url: String) {
            main.post { BuiltinBrowser.requestOpen(url) }
        }

        override fun reload() {
            main.post { BuiltinBrowser.controller?.reload() }
        }

        private fun <T> onMain(block: () -> T, fallback: T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var value = fallback
            val latch = CountDownLatch(1)
            main.post {
                value = runCatching(block).getOrDefault(fallback)
                latch.countDown()
            }
            runCatching { latch.await(2, TimeUnit.SECONDS) }
            return value
        }
    }

    private companion object {
        const val ASSET = "deepx/deepx-lite-mcp.mjs"
        const val ASSET_SKILLS = "extensions/deepx-flow/skills"
        const val ASSET_VIDEO_TOOL = "tools/qcode-video/qcode-video.zip"
        const val VIDEO_TOOL_VERSION = "1.0.0"
        const val VIDEO_TOOL_DIR = ".qcode-tools/video"
        const val VIDEO_TOOL_MARKER = ".qcode-tool-version"
        const val VIDEO_TOOL_FFMPEG = "node_modules/ffmpeg-static/ffmpeg"
        const val KEY_INSTALLED = "installed_update"
    }
}
