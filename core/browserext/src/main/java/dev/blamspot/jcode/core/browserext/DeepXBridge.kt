package dev.blamspot.jcode.core.browserext

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface DeepXSurface {
    fun currentUrl(): String
    fun isAvailable(): Boolean
    fun evaluate(script: String, onResult: (String?) -> Unit)
    fun navigate(url: String)
    fun reload()
}

class DeepXBridgeServer(
    private val surface: DeepXSurface,
    private val preferredPorts: List<Int> = listOf(8792, 8793),
) {
    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    var boundPort: Int? = null
        private set

    @Synchronized
    fun start(): Result<Int> {
        boundPort?.let { return Result.success(it) }
        var last: Throwable? = null
        for (port in preferredPorts) {
            val candidate = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                }
            } catch (failure: Throwable) {
                last = failure
                continue
            }
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            socket = candidate
            scope = newScope
            boundPort = port
            acceptJob = newScope.launch { acceptLoop(candidate, newScope) }
            return Result.success(port)
        }
        return Result.failure(last ?: IllegalStateException("Could not bind the DeepX bridge"))
    }

    @Synchronized
    fun stop() {
        boundPort = null
        runCatching { socket?.close() }
        socket = null
        acceptJob?.cancel()
        acceptJob = null
        scope?.cancel()
        scope = null
    }

    private suspend fun acceptLoop(server: ServerSocket, owner: CoroutineScope) {
        while (owner.isActive) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { client.soTimeout = 15_000 }
            owner.launch {
                client.use { connection ->
                    runCatching {
                        handleConnection(connection.getInputStream(), connection.getOutputStream())
                    }
                }
            }
        }
    }

    private suspend fun handleConnection(input: InputStream, output: OutputStream) {
        val request = readRequest(input) ?: return
        val path = request.path.substringBefore('?')
        when {
            request.method == "GET" && path == "/status" -> {
                val current = status()
                respond(
                    output,
                    200,
                    buildJsonObject {
                        put("status", "ok")
                        put("extensionConnected", true)
                        put("connectedClients", 1)
                        put("extensionInfo", buildJsonObject {
                            put("client", "qcode-native-deepx")
                            put("version", "1.0.0")
                            put("account", current["account"] ?: JsonNull)
                            put("flowTabOpen", current["flowTabOpen"] ?: JsonPrimitive(false))
                            put("projectId", current["projectId"] ?: JsonNull)
                            put("ready", current["ready"] ?: JsonPrimitive(false))
                        })
                        put("flowTab", current)
                    },
                )
            }
            request.method == "POST" && (path == "/api/command" || path == "/command") -> {
                val response = runCatching {
                    val body = Json.parseToJsonElement(request.body) as? JsonObject
                        ?: throw IllegalArgumentException("Invalid JSON body")
                    val command = body.string("command")
                        ?: throw IllegalArgumentException("command is required")
                    val payload = body["payload"] as? JsonObject ?: JsonObject(emptyMap())
                    val result = execute(command, payload)
                    buildJsonObject {
                        put("success", true)
                        put("result", result)
                    }
                }.getOrElse {
                    buildJsonObject {
                        put("success", false)
                        put("error", it.message ?: "Command failed")
                    }
                }
                respond(output, if (response["success"]?.jsonPrimitive?.content == "true") 200 else 500, response)
            }
            else -> respond(
                output,
                404,
                buildJsonObject { put("success", false); put("error", "Not found") },
            )
        }
    }

    private suspend fun execute(command: String, payload: JsonObject): JsonElement = when (command) {
        "status" -> status()
        "eval_script" -> evaluateScript(payload)
        "native_click" -> nativeClick(payload)
        "native_key" -> nativeKey(payload)
        "navigate" -> {
            val url = payload.string("url")
                ?: throw IllegalArgumentException("url is required")
            surface.navigate(url)
            buildJsonObject { put("success", true); put("url", url) }
        }
        "reload_tab" -> {
            surface.reload()
            buildJsonObject { put("reloaded", true) }
        }
        else -> throw IllegalArgumentException("Unsupported DeepX command: $command")
    }

    private suspend fun status(): JsonObject {
        val url = runCatching { surface.currentUrl() }.getOrDefault("")
        val available = runCatching { surface.isAvailable() }.getOrDefault(false)
        val flowTabOpen = available && isFlowUrl(url)
        val context = if (flowTabOpen) evaluateJson(CONTEXT_JS, 5_000) as? JsonObject else null
        val account = context?.get("account") as? JsonObject ?: buildJsonObject {
            put("isLoggedIn", false)
            put("accountName", JsonNull)
            put("email", JsonNull)
            put("plan", "None")
        }
        val isLoggedIn = account["isLoggedIn"]?.jsonPrimitive?.content == "true"
        val projectId = context?.string("projectId") ?: projectIdFromUrl(url)
        val projectName = context?.string("projectName")
        val ready = flowTabOpen && isLoggedIn
        val connectionState = when {
            !flowTabOpen -> "tab_not_found"
            !isLoggedIn -> "needs_login"
            else -> "ready"
        }
        val stateReason = when (connectionState) {
            "tab_not_found" -> "No Google Flow tab is open in QCODE Browser."
            "needs_login" -> "Google Flow is open, but no signed-in Google account was detected."
            else -> "Connected to Google Flow and ready to use."
        }
        val domain = runCatching { URI(url).host }.getOrNull().orEmpty()
        return buildJsonObject {
            put("status", if (ready) "connected" else connectionState)
            put("connected", ready)
            put("ready", ready)
            put("message", stateReason)
            put("bridge", "healthy")
            put("extension", "qcode-native-deepx")
            put("isUnlocked", true)
            put("connectionState", connectionState)
            put("stateReason", stateReason)
            put("flowActive", ready)
            put("isExtensionEnabled", true)
            put("isFlowTabActive", flowTabOpen)
            put("currentDomain", domain)
            put("flowTabOpen", flowTabOpen)
            put("flowTabUrl", url)
            put("hasToken", false)
            put("account", account)
            put("projectId", projectId?.let(::JsonPrimitive) ?: JsonNull)
            put("projectName", projectName?.let(::JsonPrimitive) ?: JsonNull)
            put("activeBatches", 0)
        }
    }

    private suspend fun evaluateScript(payload: JsonObject): JsonObject {
        val code = payload.string("code") ?: payload.string("expression") ?: ""
        val expression = normalizeExpression(code)
        val literal = JsonPrimitive(expression).toString()
        val wrapper = "(function(){try{" +
            "var src=$literal;" +
            "var tt=window.trustedTypes,p=window.__qcodeEvalPolicy;" +
            "if(tt&&tt.createPolicy&&!p){try{p=tt.createPolicy('qcode-eval'," +
            "{createScript:function(s){return s}});window.__qcodeEvalPolicy=p}catch(_){}}" +
            "if(p)src=p.createScript(src);" +
            "return {success:true,result:(0,eval)(src)}}" +
            "catch(e){return {success:false,error:String((e&&e.message)||e)}}})()"
        val result = evaluateJson(wrapper, 30_000) as? JsonObject
            ?: throw IllegalStateException("The QCODE Browser tab is not open.")
        if (result["success"]?.jsonPrimitive?.content == "false") {
            throw IllegalArgumentException(result.string("error") ?: "Script failed")
        }
        return buildJsonObject {
            put("success", true)
            put("result", result["result"] ?: JsonNull)
        }
    }

    private suspend fun nativeClick(payload: JsonObject): JsonObject {
        val x = payload["x"]?.jsonPrimitive?.intOrNull ?: 0
        val y = payload["y"]?.jsonPrimitive?.intOrNull ?: 0
        val script = "(function(){try{var el=document.elementFromPoint($x,$y);" +
            "if(!el)return {success:false,error:'No element at point'};" +
            "var target=el.closest(\"button,[role='button'],a,input,textarea,[contenteditable='true']\")||el;" +
            "target.click();return {success:true,tag:target.tagName," +
            "text:String(target.textContent||'').slice(0,120)}}" +
            "catch(e){return {success:false,error:String((e&&e.message)||e)}}})()"
        return evaluateJson(script, 10_000) as? JsonObject
            ?: throw IllegalStateException("The QCODE Browser tab is not open.")
    }

    private suspend fun nativeKey(payload: JsonObject): JsonObject {
        val key = payload.string("key") ?: "Enter"
        val code = payload.string("code") ?: key
        val keyCode = payload["keyCode"]?.jsonPrimitive?.intOrNull ?: 13
        val script = "(function(){try{var el=document.activeElement||document.body;" +
            "var init={key:${JsonPrimitive(key)},code:${JsonPrimitive(code)},keyCode:$keyCode,bubbles:true};" +
            "el.dispatchEvent(new KeyboardEvent('keydown',init));" +
            "el.dispatchEvent(new KeyboardEvent('keyup',init));" +
            "return {success:true}}" +
            "catch(e){return {success:false,error:String((e&&e.message)||e)}}})()"
        return evaluateJson(script, 10_000) as? JsonObject
            ?: throw IllegalStateException("The QCODE Browser tab is not open.")
    }

    private suspend fun evaluateJson(script: String, timeoutMs: Long): JsonElement? {
        val raw = evaluateRaw(script, timeoutMs) ?: return null
        if (raw.isBlank() || raw == "null") return null
        return runCatching { Json.parseToJsonElement(raw) }.getOrNull()
    }

    private suspend fun evaluateRaw(script: String, timeoutMs: Long): String? {
        if (!runCatching { surface.isAvailable() }.getOrDefault(false)) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                surface.evaluate(script) { raw ->
                    if (continuation.isActive) continuation.resume(raw)
                }
            }
        }
    }

    private fun normalizeExpression(raw: String): String {
        val code = raw.trim()
        if (code.isEmpty()) return "undefined"
        if (code.startsWith("(() =>") || code.startsWith("(async () =>") || code.startsWith("(function")) {
            return code
        }
        return if (code.contains("return ")) "(() => { $code })()" else code
    }

    private fun isFlowUrl(raw: String): Boolean {
        val host = runCatching { URI(raw).host?.lowercase() }.getOrNull() ?: return false
        return host == "flow.google.com" || host == "labs.google.com" || host == "labs.google"
    }

    private fun projectIdFromUrl(raw: String): String? {
        val match = Regex("""/projects?/([A-Za-z0-9_-]{6,})""").find(raw) ?: return null
        val id = match.groupValues[1]
        return id.takeUnless { it.lowercase() in SKIP_PROJECT_IDS }
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val header = ByteArrayOutputStream()
        var match = 0
        while (match < 4) {
            val byte = input.read()
            if (byte < 0) return null
            header.write(byte)
            match = when {
                match == 0 && byte == '\r'.code -> 1
                match == 1 && byte == '\n'.code -> 2
                match == 2 && byte == '\r'.code -> 3
                match == 3 && byte == '\n'.code -> 4
                byte == '\r'.code -> 1
                else -> 0
            }
            if (header.size() > MAX_HEADER_BYTES) return null
        }
        val text = String(header.toByteArray(), StandardCharsets.UTF_8)
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ', limit = 3) ?: return null
        if (requestLine.size < 2) return null
        val headers = lines.drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
            .toMap()
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) return null
            read += count
        }
        return HttpRequest(
            method = requestLine[0].uppercase(),
            path = requestLine[1],
            body = String(body, StandardCharsets.UTF_8),
        )
    }

    private fun respond(output: OutputStream, status: Int, json: JsonElement) {
        val body = json.toString().toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private companion object {
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_BODY_BYTES = 1024 * 1024
        val SKIP_PROJECT_IDS = setOf("new", "all", "recent", "templates", "trash")

        val CONTEXT_JS = """
            (function () {
              var href = location.href;
              var match = href.match(/\/projects?\/([A-Za-z0-9_-]{6,})/i);
              var skip = ["new", "all", "recent", "templates", "trash"];
              var projectId = match && skip.indexOf(match[1].toLowerCase()) === -1 ? match[1] : null;
              var heading = document.querySelector(".header-title-heading, [data-project-name], header .project-name");
              var input = document.querySelector("input[aria-label*='Project name' i], input[aria-label*='project title' i]");
              var rawTitle = (document.title || "").replace(/^Google Flow\s*[–—-]\s*/i, "").replace(/\s*[–—-]\s*Google Flow.*${'$'}/, "").trim();
              var name = String((heading && heading.textContent) || (input && input.value) || rawTitle || "").trim();
              if (/^(google flow|flow|home|projects|new project)${'$'}/i.test(name)) name = "";
              var accountEl = document.querySelector("a[aria-label*='Google Account'], button[aria-label*='Google Account'], [aria-label^='Google Account:']");
              var aria = (accountEl && accountEl.getAttribute("aria-label")) || "";
              var accountMatch = aria.match(/Google Account:\s*([^()]*?)(?:\s*\(([^)]*)\)|${'$'})/i);
              var accountName = (accountMatch && accountMatch[1] ? accountMatch[1] : "").trim() || null;
              var email = (accountMatch && accountMatch[2] ? accountMatch[2] : "").trim() || null;
              var signIn = !!document.querySelector("a[href*='accounts.google.com/ServiceLogin'], a[href*='signin']");
              var isLoggedIn = !!email || (!!accountName && !/^google user${'$'}/i.test(accountName) && !signIn);
              var planText = Array.prototype.map.call(
                document.querySelectorAll("[aria-label*='PRO'], [class*='pro-badge'], [data-plan='pro']"),
                function (el) { return el.textContent || ""; }
              ).join(" ");
              var plan = isLoggedIn ? (/PRO/i.test(planText) ? "PRO" : "Standard") : "None";
              return {
                url: href,
                projectId: projectId,
                projectName: name || null,
                account: { isLoggedIn: isLoggedIn, accountName: accountName, email: email, plan: plan }
              };
            })()
        """.trimIndent()
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
