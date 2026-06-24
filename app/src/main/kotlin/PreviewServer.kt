package com.jimmy.app

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class PreviewServer(private val siteDir: File, private val port: Int = 8080) {

    companion object {
        val log: Logger = LoggerFactory.getLogger(this::class.java)
    }

    private val sessions = Collections.newSetFromMap(ConcurrentHashMap<DefaultWebSocketServerSession, Boolean>())

    fun start() {
        embeddedServer(Netty, port = port) {
            install(WebSockets)

            routing {
                webSocket("/livereload") {
                    sessions.add(this)
                    try {
                        @Suppress("ControlFlowWithEmptyBody", "UNUSED")
                        for (frame in incoming) { }
                    } catch (_: ClosedReceiveChannelException) {
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        sessions.remove(this)
                    }
                }

                staticFiles("/assets", siteDir.resolve("assets"))
                staticFiles("/content", siteDir.resolve("content"))
                staticFiles("/", siteDir)

                get("/manifest.json") {
                    val file = siteDir.resolve("manifest.json")
                    if (file.exists()) {
                        call.respondFile(file)
                    } else {
                        call.respondText("manifest.json not found", status = io.ktor.http.HttpStatusCode.NotFound)
                    }
                }
                get("{...}") {
                    call.respondFile(siteDir.resolve("index.html"))
                }
            }
        }.start(wait = false)
        log.info("Preview server started at http://localhost:$port")
    }

    suspend fun reload() {
        val currentSessions = sessions.toList()
        currentSessions.forEach { session ->
            try {
                session.send(Frame.Text("reload"))
            } catch (e: Exception) {
                log.error("Error sending reload signal: ${e.message}")
                sessions.remove(session)
            }
        }
    }
}
