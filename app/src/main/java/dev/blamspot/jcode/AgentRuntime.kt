package dev.blamspot.jcode

import android.content.Context
import dev.blamspot.jcode.core.agent.AgentChatStore
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

    fun createClaudeController(
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
        return AgentController(
            backend = ClaudeAgentBackend(launcher),
            scope = scope,
            store = AgentChatStore(File(context.filesDir, "agent/chat.json")),
        )
    }
}
