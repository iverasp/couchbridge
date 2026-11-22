package couchbridge

data class RecipeSections(val ingredients: String?, val instructions: String?)

object Sections {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")

    fun recipe(body: String): RecipeSections = RecipeSections(
        ingredients = section(body, "Ingredienser"),
        instructions = section(body, "Instruksjoner"),
    )

    /**
     * Returns the content under the first `## [title]` heading, up to the next heading of the same or higher level,
     * with leading and trailing blank lines removed. Returns null when the heading is missing.
     */
    fun section(body: String, title: String): String? {
        val lines = body.split("\n").map { it.removeSuffix("\r") }
        var start = -1
        var level = 0
        var end = lines.size
        var fence: String? = null
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                fence = if (fence == null) marker else if (fence == marker) null else fence
                continue
            }
            if (fence != null) continue
            val match = heading.matchEntire(line) ?: continue
            val headingLevel = match.groupValues[1].length
            if (start < 0) {
                if (headingLevel == 2 && match.groupValues[2].equals(title, ignoreCase = true)) {
                    start = i + 1
                    level = headingLevel
                }
            } else if (headingLevel <= level) {
                end = i
                break
            }
        }
        if (start < 0) return null
        return lines.subList(start, end).dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    }
}
