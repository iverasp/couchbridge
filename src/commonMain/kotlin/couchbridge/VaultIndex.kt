package couchbridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.concurrent.Volatile

/** A file entry as stored by Obsidian LiveSync (a metadata document pointing at chunk documents). */
data class VaultFile(
    val id: String,
    val rev: String,
    val path: String,
    val type: String,
    val children: List<String>,
    val eden: Map<String, String>,
    val ctime: Long?,
    val mtime: Long?,
    val size: Long?,
) {
    val isBinary: Boolean get() = type == TYPE_BINARY

    companion object {
        const val TYPE_TEXT = "plain"
        const val TYPE_BINARY = "newnote"

        private val internalPrefix = Regex("^[a-z]{1,3}:")

        /** Returns null when the document is not a live, user-visible vault file. */
        fun fromDoc(doc: JsonObject): VaultFile? {
            fun str(key: String) = (doc[key] as? JsonPrimitive)?.contentOrNull
            val type = str("type")
            if (type != TYPE_TEXT && type != TYPE_BINARY) return null
            if ((doc["deleted"] as? JsonPrimitive)?.booleanOrNull == true) return null
            if ((doc["_deleted"] as? JsonPrimitive)?.booleanOrNull == true) return null
            val id = str("_id") ?: return null
            val rawPath = str("path") ?: return null
            if (internalPrefix.containsMatchIn(id) || internalPrefix.containsMatchIn(rawPath)) return null

            return VaultFile(
                id = id,
                rev = str("_rev") ?: "",
                path = Paths.normalize(rawPath),
                type = type,
                children = doc["children"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                eden = (doc["eden"] as? JsonObject)?.mapNotNull { (key, value) ->
                    ((value as? JsonObject)?.get("data") as? JsonPrimitive)?.contentOrNull?.let { key to it }
                }?.toMap() ?: emptyMap(),
                ctime = (doc["ctime"] as? JsonPrimitive)?.longOrNull,
                mtime = (doc["mtime"] as? JsonPrimitive)?.longOrNull,
                size = (doc["size"] as? JsonPrimitive)?.longOrNull,
            )
        }
    }
}

sealed interface Entry {
    data class Folder(val path: String) : Entry
    data class File(val file: VaultFile) : Entry
}

/** In-memory index of all vault files, keyed by path. Writes come from a single sync coroutine. */
class VaultIndex {
    private class State(val byPath: Map<String, VaultFile>, val idToPath: Map<String, String>)

    @Volatile
    private var state = State(emptyMap(), emptyMap())

    @Volatile
    var ready: Boolean = false
        private set

    val files: Collection<VaultFile> get() = state.byPath.values

    fun markReady() {
        ready = true
    }

    /** Applies a batch of changes. `doc == null` (or a non-file doc) removes the id. */
    fun apply(changes: List<Pair<String, JsonObject?>>) {
        if (changes.isEmpty()) return
        val byPath = state.byPath.toMutableMap()
        val idToPath = state.idToPath.toMutableMap()
        for ((id, doc) in changes) {
            idToPath.remove(id)?.let { old -> if (byPath[old]?.id == id) byPath.remove(old) }
            val file = doc?.let(VaultFile::fromDoc) ?: continue
            byPath[file.path] = file
            idToPath[id] = file.path
        }
        state = State(byPath, idToPath)
    }

    fun file(path: String): VaultFile? {
        val byPath = state.byPath
        return byPath[path] ?: byPath.values.firstOrNull { it.path.equals(path, ignoreCase = true) }
    }

    fun lookup(path: String): Entry? {
        file(path)?.let { return Entry.File(it) }
        val prefix = "$path/"
        val folder = state.byPath.keys.firstOrNull { it.startsWith(prefix, ignoreCase = true) } ?: return null
        return Entry.Folder(folder.substring(0, path.length))
    }

    /** Lists the direct children of [folder]: sub-folder paths and files. */
    fun list(folder: String): Pair<List<String>, List<VaultFile>> {
        val folders = mutableSetOf<String>()
        val files = mutableListOf<VaultFile>()
        for (file in state.byPath.values) {
            if (!Paths.isUnder(file.path, folder) || file.path == folder) continue
            val rest = Paths.relativeTo(file.path, folder)
            val slash = rest.indexOf('/')
            if (slash < 0) files += file else folders += Paths.join(folder, rest.substring(0, slash))
        }
        return folders.sortedBy { it.lowercase() } to files.sortedBy { it.path.lowercase() }
    }
}
