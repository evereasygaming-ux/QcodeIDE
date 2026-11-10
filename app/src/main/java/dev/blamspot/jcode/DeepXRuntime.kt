package dev.blamspot.jcode

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.blamspot.jcode.core.browserext.DeepXBridgeServer
import dev.blamspot.jcode.core.browserext.DeepXSurface
import dev.blamspot.jcode.core.distro.DistroService
import dev.blamspot.jcode.workbench.BuiltinBrowser
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                    if (ready) ensureStarted() else _state.value = State.Waiting
                }
        }
    }

    fun stop() {
        bridge.stop()
        observing = false
        _state.value = State.Waiting
    }

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

    private fun writeMcpConfig(rootfs: File, home: String, scriptGuestPath: String) {
        val configDir = File(rootfs, home.trimStart('/') + "/.gemini/config")
        if (!configDir.isDirectory && !configDir.mkdirs()) {
            throw IllegalStateException("Could not create ${configDir.absolutePath}")
        }
        configDir.setReadable(true, false)
        configDir.setExecutable(true, false)
        val configFile = File(configDir, "mcp_config.json")
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
        const val KEY_INSTALLED = "installed_update"
    }
}
