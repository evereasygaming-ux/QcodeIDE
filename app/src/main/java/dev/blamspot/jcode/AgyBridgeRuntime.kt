package dev.blamspot.jcode

import android.content.Context
import android.content.pm.PackageManager
import dev.blamspot.jcode.core.agent.agy.AgyBridgeClient
import dev.blamspot.jcode.core.distro.DistroService
import dev.blamspot.jcode.core.distro.GuestProcessTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.TimeZone

/**
 * Owns the vendored `agy_ide_bridge` process inside the Linux runtime.
 *
 * The bridge binary is shipped as an APK asset (built by the `buildAgyBridge` Gradle task) and
 * copied into the guest rootfs the first time it is needed: `/storage` is mounted noexec, so the
 * asset cannot be executed from there. The bridge listens on loopback, which proot shares with the
 * app, and its security token is persisted locally so a restarted app can still authenticate.
 */
class AgyBridgeRuntime(
    private val context: Context,
    private val distroService: DistroService,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data object Running : State
        data class Failed(val message: String) : State
    }

    private val prefs = context.getSharedPreferences("agy_bridge", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val log = StringBuilder()
    private var process: Process? = null

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    val httpUrl: String get() = "http://127.0.0.1:$BRIDGE_PORT"
    val wsUrl: String get() = "ws://127.0.0.1:$BRIDGE_PORT/ws"
    val framedHeader: String get() = "x-ag" + token().padEnd(TOKEN_LEN, ' ') + "_9qx"

    /** Start the bridge if it is not already listening. Returns a failure with a user-facing reason. */
    suspend fun ensureStarted(workspaceDir: String? = null): Result<Unit> = lock.withLock {
        withContext(Dispatchers.IO) {
            if (AgyBridgeClient.isHealthy(httpUrl)) return@withContext Result.success(Unit)
            if (!distroService.isRuntimeReady()) {
                return@withContext fail("Linux environment is not ready yet")
            }

            _state.value = State.Starting
            if (!installBridge()) {
                return@withContext fail("Could not install the Antigravity bridge")
            }

            val user = distroService.environmentState.value.runtime.user
            val home = guestHome(user)
            val agy = findAgy()
                ?: return@withContext fail(
                    "Antigravity CLI (agy) is not installed. Install it in the Linux environment, then retry.",
                )

            val started = startProcess(home, user, agy, workspaceDir)
                ?: return@withContext fail("Could not start the Antigravity bridge")

            repeat(HEALTH_ATTEMPTS) {
                if (AgyBridgeClient.isHealthy(httpUrl)) {
                    _state.value = State.Running
                    return@withContext Result.success(Unit)
                }
                if (!started.isAlive) {
                    process = null
                    return@withContext fail(
                        "Antigravity bridge exited (code ${started.exitValue()}): ${logTail()}",
                    )
                }
                delay(HEALTH_DELAY_MS)
            }

            process = null
            GuestProcessTree.destroy(started)
            fail("Antigravity bridge did not become ready: ${logTail()}")
        }
    }

    fun stop() {
        val running = process ?: return
        process = null
        GuestProcessTree.destroy(running)
        _state.value = State.Stopped
    }

    private fun fail(message: String): Result<Unit> {
        _state.value = State.Failed(message)
        return Result.failure(IllegalStateException(message))
    }

    private fun installBridge(): Boolean {
        val user = distroService.environmentState.value.runtime.user
        val home = guestHome(user)
        val rootfs = distroService.activeRootfsPath()
        val dir = File(rootfs, home.trimStart('/') + "/.qcode/bin")
        val target = File(dir, "agy_ide_bridge")
        val updated = runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            ).lastUpdateTime
        }.getOrDefault(0L)

        if (target.isFile && target.length() > 0 && prefs.getLong(KEY_INSTALLED, -1L) == updated) {
            target.setExecutable(true, false)
            return true
        }

        return runCatching {
            dir.mkdirs()
            val temp = File(dir, "agy_ide_bridge.tmp")
            context.assets.open(ASSET).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            temp.setReadable(true, false)
            temp.setExecutable(true, false)
            target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.setReadable(true, false)
            target.setExecutable(true, false)
            prefs.edit().putLong(KEY_INSTALLED, updated).apply()
            true
        }.getOrElse {
            appendLog("install failed: ${it.message}")
            false
        }
    }

    private suspend fun findAgy(): String? {
        val result = distroService.exec(
            command = "command -v agy 2>/dev/null || true",
            timeoutMs = 5_000,
        )
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") }
    }

    private fun startProcess(home: String, user: String, agy: String, workspaceDir: String?): Process? {
        val extraPath = listOf(
            "$home/.local/bin",
            "/root/.local/bin",
            "/root/.npm-global/bin",
            "/usr/local/bin",
            "/usr/bin",
        ).joinToString(":")
        val tzOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        val command = buildString {
            append(quote("$home/.qcode/bin/agy_ide_bridge"))
            append(" -f --no-claude")
            append(" --token ").append(token())
            append(" -p ").append(BRIDGE_PORT)
            append(" --hub-port ").append(HUB_PORT)
            append(" --tz-offset ").append(tzOffset)
            append(" --bin ").append(quote(agy))
            if (!workspaceDir.isNullOrBlank()) append(" --dir ").append(quote(workspaceDir))
        }

        val started = distroService.spawnStdioProcess(
            command = command,
            workdir = home,
            userOverride = user,
            extraPath = extraPath,
            owner = OWNER,
        ) ?: return null
        process = started

        scope.launch(Dispatchers.IO) {
            runCatching { started.inputStream.bufferedReader().forEachLine(::appendLog) }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { started.errorStream.bufferedReader().forEachLine(::appendLog) }
        }
        return started
    }

    private fun token(): String {
        prefs.getString(KEY_TOKEN, null)?.takeIf { it.length == TOKEN_LEN }?.let { return it }
        val charset = "0123456789abcdefghijklmnopqrstuvwxyz"
        val random = SecureRandom()
        val value = buildString {
            repeat(TOKEN_LEN) { append(charset[random.nextInt(charset.length)]) }
        }
        prefs.edit().putString(KEY_TOKEN, value).apply()
        return value
    }

    private fun appendLog(line: String) {
        synchronized(log) {
            log.append(line).append('\n')
            if (log.length > LOG_LIMIT) log.delete(0, log.length - LOG_LIMIT)
        }
    }

    private fun logTail(): String = synchronized(log) {
        log.lineSequence().toList().takeLast(8).joinToString(" | ").take(600)
    }

    private fun guestHome(user: String): String = if (user == "root") "/root" else "/home/$user"

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val ASSET = "agent/agy_ide_bridge"
        const val OWNER = "agy-bridge"
        const val BRIDGE_PORT = 4234
        const val HUB_PORT = 4235
        const val TOKEN_LEN = 12
        const val HEALTH_ATTEMPTS = 40
        const val HEALTH_DELAY_MS = 500L
        const val LOG_LIMIT = 8_000
        const val KEY_TOKEN = "token"
        const val KEY_INSTALLED = "installed_update"
    }
}
