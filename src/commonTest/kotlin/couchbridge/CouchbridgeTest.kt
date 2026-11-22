package couchbridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CouchbridgeTest {
    @Test
    fun parsesFrontmatter() {
        val note = Frontmatter.parse("---\ntitle: Pie\nservings: 4\ntime: 1.5\nvegan: false\ntags:\n  - dessert\n  - baking\n---\n# Pie\n")
        assertEquals(JsonPrimitive("Pie"), note.metadata["title"])
        assertEquals(JsonPrimitive(4L), note.metadata["servings"])
        assertEquals(JsonPrimitive(1.5), note.metadata["time"])
        assertEquals(JsonPrimitive(false), note.metadata["vegan"])
        assertEquals(JsonArray(listOf(JsonPrimitive("dessert"), JsonPrimitive("baking"))), note.metadata["tags"])
        assertEquals("# Pie\n", note.body)
    }

    @Test
    fun noFrontmatter() {
        val note = Frontmatter.parse("# Just text")
        assertTrue(note.metadata.isEmpty())
        assertEquals("# Just text", note.body)
    }

    @Test
    fun extractsEmbeds() {
        val md = "![[pie.jpg|300]] ![alt](<img/my%20cake.png> \"t\") ![x](https://e.com/a.png) ![[Other note]]"
        assertEquals(listOf("pie.jpg", "Other note", "img/my cake.png"), Links.embeds(md))
        assertEquals(listOf("cover.jpg"), Links.wikilinks(buildJsonObject { put("image", "[[cover.jpg]]") }))
    }

    @Test
    fun indexListsFolders() {
        val index = VaultIndex()
        fun doc(path: String) = buildJsonObject {
            put("_id", path.lowercase()); put("_rev", "1"); put("path", path); put("type", "plain")
        }
        index.apply(listOf("recipes/Cakes/choc.md", "recipes/pie.md", "other/x.md").map { it.lowercase() to doc(it) })
        val (folders, files) = index.list("recipes")
        assertEquals(listOf("recipes/Cakes"), folders)
        assertEquals(listOf("recipes/pie.md"), files.map { it.path })
        assertTrue(index.lookup("recipes/Cakes") is Entry.Folder)
        index.apply(listOf("recipes/pie.md" to null))
        assertNull(index.file("recipes/pie.md"))
    }

    @Test
    fun decodesBase64Chunks() {
        val bytes = ByteArray(100) { it.toByte() }
        val whole = Base64.encode(bytes)
        assertEquals(bytes.toList(), decodeBase64Chunks(whole.chunked(7)).toList())
        val pieces = listOf(Base64.encode(bytes.copyOfRange(0, 10)), Base64.encode(bytes.copyOfRange(10, 100)))
        assertEquals(bytes.toList(), decodeBase64Chunks(pieces).toList())
    }

    @Test
    fun extractsRecipeSections() {
        val body = "\n# Pannekaker\nIntro\n\n## Ingredienser\n\n\n- 3 egg\n- 5 dl melk\n\n## Instruksjoner  \n\n1. Bland\n```\n## Not a heading\n```\n### Tips\nStek\n\n\n# Next\nx"
        val sections = Sections.recipe(body)
        assertEquals("- 3 egg\n- 5 dl melk", sections.ingredients)
        assertEquals("1. Bland\n```\n## Not a heading\n```\n### Tips\nStek", sections.instructions)
        assertNull(Sections.recipe("# No sections").ingredients)
    }

    @Test
    fun normalizesPaths() {
        assertEquals("recipes/img/a.png", Paths.join("recipes/cakes", "../img/a.png"))
        assertEquals("recipes", Paths.normalize("/recipes/"))
    }
}
