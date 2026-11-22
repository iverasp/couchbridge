package couchbridge

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.launch
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val config = Config.fromEnv { getenv(it)?.toKString() }
    val couch = CouchClient(config)
    val index = VaultIndex()
    val vault = Vault(couch, index, config.rootFolder)

    println("couchbridge: serving '${config.rootFolder}' from ${config.couchUrl}/${config.database} on ${config.host}:${config.port}")
    embeddedServer(CIO, port = config.port, host = config.host) {
        launch { Sync(couch, index).run() }
        module(vault)
    }.start(wait = true)
}
