package couchbridge

object Paths {
    /** Normalizes a vault path: forward slashes, no leading/trailing slash, resolves `.` and `..`. */
    fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (segment in path.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> out.removeLastOrNull()
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }

    fun join(vararg parts: String): String = normalize(parts.filter { it.isNotEmpty() }.joinToString("/"))

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    fun extension(path: String): String = name(path).substringAfterLast('.', "").lowercase()

    fun isUnder(path: String, folder: String): Boolean =
        folder.isEmpty() || path == folder || path.startsWith("$folder/")

    fun relativeTo(path: String, folder: String): String =
        if (folder.isEmpty()) path else path.removePrefix(folder).removePrefix("/")

    fun isMarkdown(path: String): Boolean = extension(path) == "md"

    fun contentType(path: String): String = when (extension(path)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "pdf" -> "application/pdf"
        "md" -> "text/markdown; charset=utf-8"
        "txt" -> "text/plain; charset=utf-8"
        "json" -> "application/json"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}
