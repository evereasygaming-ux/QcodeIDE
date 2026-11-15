package dev.blamspot.jcode

import android.content.Context
import dev.blamspot.jcode.core.agent.AcpMcpServer
import dev.blamspot.jcode.core.agent.AgentChatStore
import dev.blamspot.jcode.core.agent.AgentKind
import dev.blamspot.jcode.core.agent.AgyAcpBackend
import dev.blamspot.jcode.core.agent.AgentProcessLauncher
import dev.blamspot.jcode.core.agent.ClaudeAgentBackend
import dev.blamspot.jcode.core.browserext.BundledBrowserExtensions
import dev.blamspot.jcode.core.browserext.BundledExtensionPreferences
import dev.blamspot.jcode.core.distro.DistroService
import dev.blamspot.jcode.feature.agent.AgentController
import dev.blamspot.jcode.feature.agent.AgentSetupSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import java.io.File

/**
 * Builds agent backends on top of the embedded Linux runtime.
 *
 * The agent CLI is a normal guest process: it runs as the runtime user, in the runtime workdir, and
 * its stdio pipes carry the protocol. Keeping the wiring here (rather than in `:feature:agent`)
 * leaves the feature module testable with a fake [AgentProcessLauncher].
 */
object AgentRuntime {

    fun createController(
        context: Context,
        distroService: DistroService,
        scope: CoroutineScope,
    ): AgentController {
        val user = distroService.environmentState.value.runtime.user
        // The CLI may be installed through npm, the native installer, or a distro package; prepend
        // the usual per-user and global bin directories so all three resolve.
        val extraPath = listOf(
            "/root/.local/bin",
            "/home/$user/.local/bin",
            "/root/.npm-global/bin",
        ).joinToString(":")

        val launcher = AgentProcessLauncher { command, workdir ->
            if (workdir != null) {
                distroService.spawnStdioProcess(
                    command = command,
                    workdir = workdir,
                    extraPath = extraPath,
                    owner = "agent",
                )
            } else {
                distroService.spawnStdioProcess(
                    command = command,
                    extraPath = extraPath,
                    owner = "agent",
                )
            }
        }
        val storeDir = File(context.filesDir, "agent")
        val rootfs = distroService.activeRootfsPath()
        val home = if (user == "root") "/root" else "/home/$user"
        val deepxScript = "$home/.qcode/deepx/deepx-lite-mcp.mjs"
        // The guest is not a shell we can ask; the installer's output path is a fact on disk. If the
        // binary is already there the pane skips the install card and goes straight to chat.
        val agyInstalled = listOf("$home/.local/bin/agy", "/root/.local/bin/agy", "/usr/local/bin/agy")
            .any { File(rootfs, it.trimStart('/')).isFile }
        val agyBackend = AgyAcpBackend(
            launcher = launcher,
            defaultWorkdir = distroService.environmentState.value.runtime.workdir,
            cliInstalled = agyInstalled,
            mcpServers = {
                // The extension manager's switch decides whether the agent gets the Flow tools at
                // all; a disabled extension must not leave a dead server in every new session.
                if (BundledExtensionPreferences(context).isEnabled(BundledBrowserExtensions.DEEPX_FLOW_ID)) {
                    resolveDeepxServer(rootfs, home, deepxScript)?.let(::listOf).orEmpty()
                } else {
                    emptyList()
                }
            },
        )
        return AgentController(
            backends = linkedMapOf(
                AgentKind.CLAUDE to ClaudeAgentBackend(launcher),
                AgentKind.AGY to agyBackend,
            ),
            // The install/sign-in command the Agent pane offers has to run in the guest with the
            // runtime user's environment, not through the stdio launcher (which owns a protocol pipe).
            setupRunner = { command, onLine ->
                // timeoutMs 0 = no ceiling: the official installer downloads a ~100 MB binary.
                val result = distroService.exec(
                    command = command,
                    timeoutMs = 0L,
                    onLine = onLine,
                )
                if (!result.succeeded) {
                    val reason = result.internalError
                        ?: result.stderr.lineSequence().lastOrNull { it.isNotBlank() }
                        ?: "exit ${result.exitCode ?: "?"}"
                    error("Setup failed: $reason")
                }
            },
            // `agy acp --login` keeps running after it prints the URL: it reads the authorization
            // code from stdin. exec() has no stdin, so the login gets a real stdio process whose
            // input the pane can feed while the output streams into the card.
            setupSessionStarter = { command, onLine, onExit ->
                val process = distroService.spawnStdioProcess(
                    command = command,
                    workdir = distroService.environmentState.value.runtime.workdir,
                    extraPath = extraPath,
                    owner = "agent-setup",
                )
                process?.let { live ->
                    StdinSetupSession(live, scope, onLine, onExit)
                }
            },
            // Setup runs through exec(), not the stdio launcher, so the backend tracks the outcome
            // itself: install → sign-in card, sign-in → no card, and a fresh process either way.
            onSetupComplete = { command -> agyBackend.onSetupFinished(command) },
            scope = scope,
            stores = mapOf(
                AgentKind.CLAUDE to AgentChatStore(File(storeDir, "chat.json")),
                AgentKind.AGY to AgentChatStore(File(storeDir, "chat-agy.json")),
            ),
        )
    }

    private fun resolveDeepxServer(rootfs: File, home: String, script: String): AcpMcpServer? {
        if (!File(rootfs, script.trimStart('/')).isFile) return null
        val node = resolveNode(rootfs, home) ?: return null
        return AcpMcpServer(name = "corex-flow", command = node, args = listOf(script))
    }

    private fun resolveNode(rootfs: File, home: String): String? {
        listOf("/usr/local/bin/node", "/usr/bin/node", "/bin/node")
            .firstOrNull { File(rootfs, it.trimStart('/')).exists() }
            ?.let { return it }
        val nvm = File(rootfs, home.trimStart('/') + "/.nvm/versions/node")
        return nvm.listFiles()
            ?.sortedByDescending { it.name }
            ?.firstOrNull { File(it, "bin/node").exists() }
            ?.let { "$home/.nvm/versions/node/${it.name}/bin/node" }
    }
}

/**
 * A setup command with a live stdin pipe.
 *
 * Setup commands normally complete by themselves; the agy login is the exception, because its
 * second half is the authorization code. [onExit] fires only while the session is still open, so
 * a dismissed card cannot resurrect itself when the killed process finally reaps.
 */
private class StdinSetupSession(
    private val process: Process,
    scope: CoroutineScope,
    onLine: (String) -> Unit,
    onExit: (Int?) -> Unit,
) : AgentSetupSession {
    private val alive = java.util.concurrent.atomic.AtomicBoolean(true)

    init {
        val stdout = thread(name = "agy-login-out", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (alive.get()) onLine(line)
                }
            }
        }
        val stderr = thread(name = "agy-login-err", isDaemon = true) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (alive.get()) onLine(line)
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            val code = runCatching { process.waitFor() }.getOrNull()
            runCatching { stdout.join(2_000) }
            runCatching { stderr.join(2_000) }
            if (alive.get()) onExit(code)
        }
    }

    override fun submit(text: String) {
        runCatching {
            process.outputStream.write((text.trimEnd() + "\n").toByteArray())
            process.outputStream.flush()
        }
    }

    override fun close() {
        if (alive.compareAndSet(true, false)) runCatching { process.destroy() }
    }
}
