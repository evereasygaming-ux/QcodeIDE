package dev.blamspot.jcode

import android.content.Context
import dev.blamspot.jcode.core.agent.AcpMcpServer
import dev.blamspot.jcode.core.agent.AgentChatStore
import dev.blamspot.jcode.core.agent.AgentKind
import dev.blamspot.jcode.core.agent.AgyAcpBackend
import dev.blamspot.jcode.core.agent.AgentProcessLauncher
import dev.blamspot.jcode.core.agent.ClaudeAgentBackend
import dev.blamspot.jcode.core.distro.DistroService
import dev.blamspot.jcode.feature.agent.AgentController
import kotlinx.coroutines.CoroutineScope
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
        val agyBackend = AgyAcpBackend(
            launcher = launcher,
            defaultWorkdir = distroService.environmentState.value.runtime.workdir,
            mcpServers = {
                resolveDeepxServer(rootfs, home, deepxScript)?.let(::listOf).orEmpty()
            },
        )
        return AgentController(
            backends = linkedMapOf(
                AgentKind.CLAUDE to ClaudeAgentBackend(launcher),
                AgentKind.AGY to agyBackend,
            ),
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
        return AcpMcpServer(name = "deepx-flow", command = node, args = listOf(script))
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
