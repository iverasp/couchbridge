package couchbridge

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class Item(
    val type: String,
    val name: String,
    val path: String,
    val url: String,
    val rawUrl: String? = null,
    val size: Long? = null,
    val ctime: Long? = null,
    val mtime: Long? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class Folder(
    val type: String,
    val name: String,
    val path: String,
    val parent: String?,
    val items: List<Item>,
)

@Serializable
data class Attachment(val link: String, val path: String?, val url: String?, val contentType: String?)

@Serializable
data class Document(
    val type: String,
    val name: String,
    val path: String,
    val rawUrl: String,
    val size: Long? = null,
    val ctime: Long? = null,
    val mtime: Long? = null,
    val metadata: JsonObject,
    val metadataError: String? = null,
    val body: String,
    val ingredients: String? = null,
    val instructions: String? = null,
    val attachments: List<Attachment>,
)

@Serializable
data class FileInfo(
    val type: String,
    val name: String,
    val path: String,
    val rawUrl: String,
    val contentType: String,
    val size: Long? = null,
    val ctime: Long? = null,
    val mtime: Long? = null,
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class Health(val status: String, val files: Int)

private const val TYPE_FOLDER = "folder"
private const val TYPE_DOCUMENT = "document"
private const val TYPE_FILE = "file"

fun Application.module(vault: Vault) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = false; explicitNulls = false })
    }
    install(StatusPages) {
        exception<NotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message!!)) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message!!)) }
        exception<Throwable> { call, e ->
            println("Request failed: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    val api = Api(vault)
    routing {
        get("/health") {
            val ready = vault.index.ready
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                Health(if (ready) "ok" else "syncing", vault.index.files.size),
            )
        }
        get("/contents") { api.contents(call, "") }
        get("/contents/{path...}") { api.contents(call, call.tailPath()) }
        get("/raw/{path...}") { api.raw(call, call.tailPath()) }
    }
}

private fun ApplicationCall.tailPath(): String = parameters.getAll("path").orEmpty().joinToString("/")

private class Api(private val vault: Vault) {
    /** Returns a folder listing, a document (metadata + body) or file info, depending on what [apiPath] points at. */
    suspend fun contents(call: ApplicationCall, apiPath: String) {
        if (!vault.index.ready) return call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Index is syncing"))
        val path = vault.toVaultPath(apiPath)
        if (path == vault.root) return folder(call, path)
        when (val entry = vault.index.lookup(path)) {
            is Entry.Folder -> folder(call, entry.path)
            is Entry.File ->
                if (Paths.isMarkdown(entry.file.path)) document(call, entry.file) else file(call, entry.file)
            null -> throw NotFoundException("'$apiPath' not found")
        }
    }

    /** Raw file bytes. Takes a full vault path, so attachments outside the root folder can be served too. */
    suspend fun raw(call: ApplicationCall, vaultPath: String) {
        if (!vault.index.ready) return call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Index is syncing"))
        val file = vault.index.file(Paths.normalize(vaultPath))?.takeIf(vault::isServable)
            ?: throw NotFoundException("'$vaultPath' not found")
        call.respondBytes(vault.readBytes(file), ContentType.parse(Paths.contentType(file.path)))
    }

    private suspend fun folder(call: ApplicationCall, folder: String) {
        val withMetadata = call.request.queryParameters["metadata"]?.let { it == "true" || it == "1" } ?: false
        val (folders, files) = vault.index.list(folder)
        val folderItems = folders.map { Item(TYPE_FOLDER, Paths.name(it), vault.toApiPath(it), contentsUrl(it)) }
        val fileItems = files.map { file ->
            val markdown = Paths.isMarkdown(file.path)
            Item(
                type = if (markdown) TYPE_DOCUMENT else TYPE_FILE,
                name = Paths.name(file.path),
                path = vault.toApiPath(file.path),
                url = contentsUrl(file.path),
                rawUrl = rawUrl(file),
                size = file.size,
                ctime = file.ctime,
                mtime = file.mtime,
                metadata = if (withMetadata && markdown) vault.note(file).parsed.metadata else null,
            )
        }
        call.respond(
            Folder(
                type = TYPE_FOLDER,
                name = Paths.name(vault.toApiPath(folder)),
                path = vault.toApiPath(folder),
                parent = if (folder == vault.root) null else vault.toApiPath(Paths.parent(folder)),
                items = folderItems + fileItems,
            )
        )
    }

    private suspend fun document(call: ApplicationCall, file: VaultFile) {
        val note = vault.note(file)
        val attachments = note.embeds.mapNotNull { link ->
            val target = vault.resolve(link, file.path)
            when {
                target == null ->
                    if (Paths.extension(link).isEmpty() || Paths.isMarkdown(link)) null
                    else Attachment(link, null, null, null)
                Paths.isMarkdown(target.path) -> null
                else -> Attachment(link, target.path, rawUrl(target), Paths.contentType(target.path))
            }
        }
        val sections = Sections.recipe(note.parsed.body)
        call.respond(
            Document(
                type = TYPE_DOCUMENT,
                name = Paths.name(file.path),
                path = vault.toApiPath(file.path),
                rawUrl = rawUrl(file),
                size = file.size,
                ctime = file.ctime,
                mtime = file.mtime,
                metadata = note.parsed.metadata,
                metadataError = note.parsed.metadataError,
                body = note.parsed.body,
                ingredients = sections.ingredients,
                instructions = sections.instructions,
                attachments = attachments,
            )
        )
    }

    private suspend fun file(call: ApplicationCall, file: VaultFile) {
        call.respond(
            FileInfo(
                type = TYPE_FILE,
                name = Paths.name(file.path),
                path = vault.toApiPath(file.path),
                rawUrl = rawUrl(file),
                contentType = Paths.contentType(file.path),
                size = file.size,
                ctime = file.ctime,
                mtime = file.mtime,
            )
        )
    }

    private fun contentsUrl(vaultPath: String): String {
        val path = vault.toApiPath(vaultPath)
        return if (path.isEmpty()) "/contents" else "/contents/${path.encodeURLPath()}"
    }

    private fun rawUrl(file: VaultFile) = "/raw/${file.path.encodeURLPath()}"
}
