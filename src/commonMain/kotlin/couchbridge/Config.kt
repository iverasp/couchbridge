package couchbridge

data class Config(
    val couchUrl: String,
    val couchUser: String?,
    val couchPassword: String?,
    val database: String,
    val rootFolder: String,
    val host: String,
    val port: Int,
) {
    companion object {
        fun fromEnv(env: (String) -> String?): Config {
            fun value(name: String): String? = env(name)?.takeIf { it.isNotBlank() }
            return Config(
                couchUrl = (value("COUCHDB_URL") ?: "http://couchdb:5984").trimEnd('/'),
                couchUser = value("COUCHDB_USER"),
                couchPassword = value("COUCHDB_PASSWORD"),
                database = value("COUCHDB_DATABASE") ?: error("COUCHDB_DATABASE must be set"),
                rootFolder = Paths.normalize(value("ROOT_FOLDER") ?: "recipes"),
                host = value("HOST") ?: "0.0.0.0",
                port = value("PORT")?.toInt() ?: 8080,
            )
        }
    }
}
