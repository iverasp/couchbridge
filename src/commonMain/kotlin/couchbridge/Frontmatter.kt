package couchbridge

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ParsedNote(
    val metadata: JsonObject,
    val body: String,
    val metadataError: String? = null,
)

object Frontmatter {
    private val integer = Regex("^[-+]?(0|[1-9][0-9]*)$")
    private val decimal = Regex("^[-+]?([0-9]*\\.[0-9]+|[0-9]+\\.[0-9]*)([eE][-+]?[0-9]+)?$")

    fun parse(markdown: String): ParsedNote {
        val lines = markdown.removePrefix("\uFEFF").split("\n")
        if (lines.firstOrNull()?.trimEnd() != "---") return ParsedNote(JsonObject(emptyMap()), markdown)
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd().let { l -> l == "---" || l == "..." } }
            ?: return ParsedNote(JsonObject(emptyMap()), markdown)

        val yaml = lines.subList(1, end).joinToString("\n") { it.removeSuffix("\r") }
        val body = lines.subList(end + 1, lines.size).joinToString("\n")
        if (yaml.isBlank()) return ParsedNote(JsonObject(emptyMap()), body)

        return try {
            val json = toJson(Yaml.default.parseToYamlNode(yaml))
            ParsedNote(json as? JsonObject ?: JsonObject(emptyMap()), body)
        } catch (e: Exception) {
            ParsedNote(JsonObject(emptyMap()), body, e.message ?: "Invalid YAML frontmatter")
        }
    }

    fun toJson(node: YamlNode): JsonElement = when (node) {
        is YamlNull -> JsonNull
        is YamlMap -> JsonObject(node.entries.entries.associate { (k, v) -> k.content to toJson(v) })
        is YamlList -> JsonArray(node.items.map(::toJson))
        is YamlTaggedNode -> toJson(node.innerNode)
        is YamlScalar -> scalar(node.content)
    }

    private fun scalar(value: String): JsonElement = when {
        value == "true" || value == "True" || value == "TRUE" -> JsonPrimitive(true)
        value == "false" || value == "False" || value == "FALSE" -> JsonPrimitive(false)
        value == "~" || value == "null" || value == "Null" || value == "NULL" -> JsonNull
        integer.matches(value) -> value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        decimal.matches(value) -> value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        else -> JsonPrimitive(value)
    }
}
