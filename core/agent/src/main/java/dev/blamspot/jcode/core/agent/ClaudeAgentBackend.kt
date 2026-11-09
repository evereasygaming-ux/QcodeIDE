package dev.blamspot.jcode.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Claude Code over its `stream-json` stdio protocol.
 *
 * The CLI runs inside the embedded Linux runtime (the launcher is supplied by the app and wraps
 * [dev.blamspot.jcode.core.distro.DistroService.spawnStdioProcess]). One process serves one turn:
 * the prompt is written as a user frame, stdout is parsed line by line, and a tool that needs the
 * user's consent is answered with a `control_response` on stdin.
 */
class ClaudeAgentBackend(
    private val launcher: AgentProcessLauncher,
    private val cliPath: String = "claude",
) : AgentBackend {

    override val kind: AgentKind = AgentKind.CLAUDE

    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingCalls = ConcurrentHashMap<String, AgentToolCall>()

    @Volatile
    private var activeProcess: Process? = null

    override fun availableModels(): List<AgentModel> = listOf(
        AgentModel("default", "Default"),
        AgentModel("sonnet", "Sonnet"),
        AgentModel("opus", "Opus"),
        AgentModel("haiku", "Haiku"),
    )

    override fun resolvePermission(requestId: String, allow: Boolean) {
        approvals.computeIfAbsent(requestId) { CompletableDeferred() }.complete(allow)
    }

    override fun stream(request: AgentRequest): Flow<AgentEvent> = channelFlow {
        val process = launchProcess(request)
        if (process == null) {
            send(
                AgentEvent.Error(
                    "Could not start Claude Code. Make sure the Linux environment is installed " +
                        "and the `claude` CLI is available in it.",
                ),
            )
            close()
            return@channelFlow
        }
        activeProcess = process

        val produced = AtomicBoolean(false)
        val stderr = StringBuilder()
        val parser = ClaudeStreamSession()

        launch(Dispatchers.IO) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderr) {
                        if (stderr.length < STDERR_LIMIT) stderr.append(line).append('\n')
                    }
                }
            }
        }

        launch(Dispatchers.IO) {
            runCatching {
                process.outputStream.write((ClaudeStreamJson.userMessage(request.prompt) + "\n").toByteArray())
                process.outputStream.flush()
            }
        }

        val reader = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        for (event in parser.parse(line)) {
                            produced.set(true)
                            if (event is AgentEvent.PermissionRequest) {
                                // Register before emitting so a fast approval cannot race the await.
                                val deferred = approvals.computeIfAbsent(event.requestId) { CompletableDeferred() }
                                pendingCalls[event.requestId] = event.call
                                send(event)
                                val allow = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
                                approvals.remove(event.requestId)
                                val call = pendingCalls.remove(event.requestId)
                                runCatching {
                                    val reply = ClaudeStreamJson.controlResponse(
                                        requestId = event.requestId,
                                        allow = allow,
                                        toolUseId = call?.id,
                                        inputJson = call?.inputJson,
                                    )
                                    process.outputStream.write((reply + "\n").toByteArray())
                                    process.outputStream.flush()
                                }
                            } else {
                                send(event)
                            }
                            if (event is AgentEvent.TurnComplete) return@useLines
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Process killed or stream closed mid-turn; the collector sees the turn end below.
            }
        }

        try {
            reader.join()
        } finally {
            reader.cancel()
            runCatching { process.outputStream.close() }
            runCatching { process.destroy() }
            if (activeProcess === process) activeProcess = null
        }

        if (!produced.get()) {
            val details = synchronized(stderr) { stderr.toString().trim() }
            send(
                AgentEvent.Error(
                    details.ifBlank {
                        "Claude exited without producing output. Check that the CLI is installed " +
                            "and signed in inside the Linux environment."
                    },
                ),
            )
        }
        close()
    }

    override fun cancel() {
        activeProcess?.let { runCatching { it.destroy() } }
        activeProcess = null
    }

    override fun close() {
        cancel()
        approvals.clear()
        pendingCalls.clear()
    }

    private fun launchProcess(request: AgentRequest): Process? =
        runCatching { launcher.launch(buildCommand(request), request.workdir) }.getOrNull()

    private fun buildCommand(request: AgentRequest): String {
        val args = mutableListOf(
            cliPath,
            "--output-format", "stream-json",
            "--verbose",
            "--input-format", "stream-json",
            "--permission-prompt-tool", "stdio",
            "--include-partial-messages",
            "--replay-user-messages",
            "--setting-sources=user,project,local",
            "--thinking-display", "summarized",
        )
        request.model?.takeIf { it.isNotBlank() && it != "default" }?.let {
            args += listOf("--model", it)
        }
        request.permissionMode.takeIf { it.isNotBlank() }?.let {
            args += listOf("--permission-mode", it)
        }
        request.resumeSessionId?.takeIf { it.isNotBlank() }?.let {
            args += "--resume=$it"
        }
        return args.joinToString(" ") { shellQuote(it) }
    }

    private fun shellQuote(value: String): String =
        if (value.matches(SHELL_SAFE)) {
            value
        } else {
            "'" + value.replace("'", "'\\''") + "'"
        }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 5 * 60_000L
        const val STDERR_LIMIT = 4_000
        val SHELL_SAFE = Regex("[A-Za-z0-9_./:=@+,-]+")
    }
}
