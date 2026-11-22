package couchbridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

class CouchException(message: String) : Exception(message)

class CouchClient(private val config: Config) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dbUrl = "${config.couchUrl}/${config.database.encodeURLPathPart()}"
    private val auth = config.couchUser?.let {
        "Basic " + Base64.encode("$it:${config.couchPassword ?: ""}".encodeToByteArray())
    }

    // Curl supports TLS on Kotlin/Native; the CIO client engine does not.
    private val http = HttpClient(Curl) {
        install(HttpTimeout) {
            requestTimeoutMillis = LONGPOLL_TIMEOUT_MS + 60_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    private suspend fun postJson(path: String, body: JsonObject): JsonObject {
        val response: HttpResponse = http.post("$dbUrl/$path") {
            auth?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw CouchException("CouchDB ${response.status}: $text")
        return json.parseToJsonElement(text).jsonObject
    }

    data class Changes(val lastSeq: String, val changes: List<Pair<String, JsonObject?>>)

    /** Fetches changes to file metadata documents (chunks are ignored). */
    suspend fun changes(since: String, longpoll: Boolean, limit: Int? = null): Changes {
        val params = buildList {
            add("filter=_selector")
            add("include_docs=true")
            add("since=${since.encodeURLParameter()}")
            if (longpoll) {
                add("feed=longpoll")
                add("timeout=$LONGPOLL_TIMEOUT_MS")
            }
            if (limit != null) add("limit=$limit")
        }.joinToString("&")
        val result = postJson("_changes?$params", FILE_SELECTOR)
        val changes = result["results"]!!.jsonArray.map { element ->
            val row = element.jsonObject
            val id = row["id"]!!.jsonPrimitive.content
            val deleted = (row["deleted"] as? JsonPrimitive)?.booleanOrNull == true
            id to if (deleted) null else row["doc"] as? JsonObject
        }
        return Changes(result["last_seq"]!!.jsonPrimitive.content, changes)
    }

    /** Loads chunk data by id. */
    suspend fun chunks(ids: Collection<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (batch in ids.distinct().chunked(200)) {
            val body = buildJsonObject { putJsonArray("keys") { batch.forEach { add(JsonPrimitive(it)) } } }
            val rows = postJson("_all_docs?include_docs=true", body)["rows"]!!.jsonArray
            for (row in rows) {
                val doc = row.jsonObject["doc"] as? JsonObject ?: continue
                val id = (doc["_id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val data = (doc["data"] as? JsonPrimitive)?.contentOrNull ?: continue
                result[id] = data
            }
        }
        return result
    }

    companion object {
        const val LONGPOLL_TIMEOUT_MS = 55_000L

        private val FILE_SELECTOR = buildJsonObject {
            putJsonObject("selector") {
                putJsonArray("\$or") {
                    add(buildJsonObject {
                        putJsonObject("type") {
                            put("\$in", buildJsonArray {
                                add(JsonPrimitive(VaultFile.TYPE_TEXT))
                                add(JsonPrimitive(VaultFile.TYPE_BINARY))
                            })
                        }
                    })
                    add(buildJsonObject { put("_deleted", true) })
                }
            }
        }
    }
}

/** Keeps a [VaultIndex] in sync with CouchDB using the `_changes` feed. */
class Sync(private val couch: CouchClient, private val index: VaultIndex) {
    suspend fun run() {
        var since = "0"
        while (true) {
            try {
                if (!index.ready) {
                    val page = couch.changes(since, longpoll = false, limit = PAGE_SIZE)
                    index.apply(page.changes)
                    since = page.lastSeq
                    if (page.changes.size < PAGE_SIZE) {
                        index.markReady()
                        println("Initial sync complete: ${index.files.size} files indexed")
                    }
                } else {
                    val page = couch.changes(since, longpoll = true)
                    index.apply(page.changes)
                    since = page.lastSeq
                }
            } catch (e: Exception) {
                println("Sync error: ${e.message}. Retrying in 5s")
                delay(5_000)
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 1000
    }
}
