package dev.blamspot.jcode.feature.agent

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One file staged for the agent to read.
 *
 * The bytes are copied out of the source provider into a folder the agent's shell can see, because
 * the agent (a CLI inside the proot guest) can only open paths — a `content://` URI means nothing
 * to it. [guestPath] is therefore the honest contract: it is what the prompt names, and it is what
 * the agent's Read tool opens.
 */
data class AgentAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mime: String,
    val isImage: Boolean,
    /** Absolute Android path the bytes were copied to. */
    val hostPath: String,
    /** The same file as the agent's shell sees it. */
    val guestPath: String,
)

/**
 * Copies picked documents into the agent-visible uploads folder and clears it on a new chat.
 *
 * The folder lives at the top of the phone's shared storage (`/storage/emulated/0/.agent-uploads`)
 * because every proot spawn binds that volume to `/sdcard` — so the agent reaches the file at
 * `/sdcard/.agent-uploads/<name>` no matter which project it is working in. When the shared volume
 * is not readable (all-files access not granted), the copy falls back to app-private storage and
 * the attachment says so, rather than handing the agent a path it cannot open.
 */
class AgentAttachmentStore(private val context: Context) {

    private val externalRoot: String? =
        runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
            ?.takeIf { root -> File(root).let { it.isDirectory && it.canRead() } }

    /** Host-side uploads folder; under the shared volume when that volume is usable. */
    private val hostDir: File =
        (externalRoot?.let { File(it, ".agent-uploads") } ?: File(context.filesDir, "agent-uploads"))
            .apply { if (!isDirectory) mkdirs() }

    /** The prefix the agent's shell will see this folder under, or null when it cannot. */
    private val guestDirPrefix: String? =
        externalRoot?.let { "/sdcard" + hostDir.absolutePath.removePrefix(it) }

    /**
     * Copies every picked URI into the uploads folder. Partial failure is per-file: a document the
     * provider refuses to stream is skipped, and the rest still attach.
     */
    suspend fun copyIn(uris: List<Uri>): List<AgentAttachment> = withContext(Dispatchers.IO) {
        if (!hostDir.isDirectory) hostDir.mkdirs()
        val resolver = context.contentResolver
        val out = ArrayList<AgentAttachment>(uris.size)
        for (uri in uris) {
            runCatching {
                val meta = queryMeta(resolver, uri)
                val stamp = System.currentTimeMillis()
                val safeName = "${stamp}_${meta.name.ifBlank { "file" }}"
                val target = File(hostDir, safeName)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: error("No stream for $uri")
                if (target.length() <= 0L) error("Empty attachment ${meta.name}")
                out.add(
                    AgentAttachment(
                        name = meta.name.ifBlank { safeName },
                        mime = meta.mime,
                        isImage = meta.mime.startsWith("image/"),
                        hostPath = target.absolutePath,
                        guestPath = guestDirPrefix?.let { prefix ->
                            prefix.removeSuffix("/") + "/" + safeName
                        } ?: target.absolutePath,
                    ),
                )
            }
            // A single unreadable provider is not worth failing the batch over.
        }
        out
    }

    /** Wipes staged uploads — called on a new chat so the folder does not grow without bound. */
    fun clear() {
        runCatching {
            hostDir.deleteRecursively()
            hostDir.mkdirs()
        }
    }

    private data class Meta(val name: String, val mime: String)

    private fun queryMeta(resolver: android.content.ContentResolver, uri: Uri): Meta {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?.substringAfterLast('/')
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .orEmpty()
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return Meta(name, mime)
    }
}
