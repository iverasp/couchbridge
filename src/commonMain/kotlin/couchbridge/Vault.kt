package couchbridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64

class NotFoundException(message: String) : Exception(message)
class BadRequestException(message: String) : Exception(message)

data class Note(
    val file: VaultFile,
    val parsed: ParsedNote,
    val embeds: List<String>,
)

/** Reads file content out of CouchDB chunks and resolves Obsidian links. */
class Vault(private val couch: CouchClient, val index: VaultIndex, val root: String) {
    @Volatile
    private var noteCache: Map<String, Note> = emptyMap()

    suspend fun readText(file: VaultFile): String = loadChunks(file).joinToString("")

    suspend fun readBytes(file: VaultFile): ByteArray {
        val chunks = loadChunks(file)
        if (!file.isBinary) return chunks.joinToString("").encodeToByteArray()
        return decodeBase64Chunks(chunks)
    }

    suspend fun note(file: VaultFile): Note {
        noteCache[file.id]?.takeIf { it.file.rev == file.rev }?.let { return it }
        val parsed = Frontmatter.parse(readText(file))
        val note = Note(file, parsed, (Links.embeds(parsed.body) + Links.wikilinks(parsed.metadata)).distinct())
        noteCache = noteCache + (file.id to note)
        return note
    }

    private suspend fun loadChunks(file: VaultFile): List<String> {
        val missing = file.children.filter { it !in file.eden }
        val fetched = if (missing.isEmpty()) emptyMap() else couch.chunks(missing)
        return file.children.map { id ->
            file.eden[id] ?: fetched[id] ?: throw CouchException("Missing chunk $id for ${file.path}")
        }
    }

    /** Resolves a link the way Obsidian does: relative/absolute path first, then shortest path matching by name. */
    fun resolve(link: String, from: String): VaultFile? {
        val target = Paths.normalize(link)
        if (target.isEmpty()) return null
        val candidates = buildList {
            add(target)
            add(Paths.join(Paths.parent(from), link))
            if (Paths.extension(target).isEmpty()) add("$target.md")
        }
        for (candidate in candidates) index.file(candidate)?.let { return it }

        val names = listOf(target) + if (Paths.extension(target).isEmpty()) listOf("$target.md") else emptyList()
        return index.files
            .filter { f -> names.any { f.path.equals(it, true) || f.path.endsWith("/$it", ignoreCase = true) } }
            .minByOrNull { it.path.length }
    }

    /** A file can be served if it lives under the root folder, or is a binary attachment anywhere in the vault. */
    fun isServable(file: VaultFile): Boolean = Paths.isUnder(file.path, root) || file.isBinary

    fun toApiPath(vaultPath: String): String = Paths.relativeTo(vaultPath, root)

    fun toVaultPath(apiPath: String): String = Paths.join(root, Paths.normalize(apiPath))
}

object Links {
    private val wikiEmbed = Regex("""!\[\[([^\]]+)]]""")
    private val wikiLink = Regex("""\[\[([^\]]+)]]""")
    private val markdownEmbed = Regex("""!\[[^\]]*]\(([^)]+)\)""")

    fun embeds(markdown: String): List<String> {
        val result = mutableListOf<String>()
        wikiEmbed.findAll(markdown).forEach { result += cleanWiki(it.groupValues[1]) }
        markdownEmbed.findAll(markdown).forEach { m -> cleanMarkdown(m.groupValues[1])?.let { result += it } }
        return result.filter { it.isNotBlank() }.distinct()
    }

    /** Collects `[[links]]` from frontmatter string values, e.g. `image: "[[pie.jpg]]"`. */
    fun wikilinks(metadata: JsonObject): List<String> {
        val result = mutableListOf<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.values.forEach(::walk)
                is JsonArray -> element.forEach(::walk)
                is JsonPrimitive -> element.contentOrNull?.let { text ->
                    wikiLink.findAll(text).forEach { result += cleanWiki(it.groupValues[1]) }
                }
            }
        }
        walk(metadata)
        return result.filter { it.isNotBlank() }.distinct()
    }

    private fun cleanWiki(raw: String): String = raw.substringBefore('|').substringBefore('#').trim()

    private fun cleanMarkdown(raw: String): String? {
        val trimmed = raw.trim()
        val target = if (trimmed.startsWith("<")) trimmed.removePrefix("<").substringBefore('>')
        else trimmed.substringBefore(' ')
        if (target.contains("://") || target.startsWith("data:")) return null
        return percentDecode(target.substringBefore('#').substringBefore('?'))
    }

    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes += hex.toByte()
                    i += 3
                    continue
                }
            }
            c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}

/**
 * LiveSync stores binary files as base64 split over several chunks. Depending on the plugin version the chunks are
 * either slices of one base64 string or individually encoded pieces, so handle both.
 */
fun decodeBase64Chunks(chunks: List<String>): ByteArray {
    val cleaned = chunks.map { chunk -> chunk.filterNot { it.isWhitespace() } }
    val independentlyPadded = cleaned.dropLast(1).any { it.contains('=') || it.length % 4 != 0 }
    return if (independentlyPadded && cleaned.all { it.length % 4 == 0 }) {
        cleaned.fold(ByteArray(0)) { acc, chunk -> acc + Base64.decode(chunk) }
    } else {
        Base64.decode(cleaned.joinToString(""))
    }
}
