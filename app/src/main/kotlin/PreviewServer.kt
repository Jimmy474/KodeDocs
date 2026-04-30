package com.jimmy.app

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class PreviewServer(private val siteDir: File, private val port: Int = 8080) {
    private val sessions = Collections.newSetFromMap(ConcurrentHashMap<DefaultWebSocketServerSession, Boolean>())

    fun start() {
        embeddedServer(Netty, port = port) {
            install(WebSockets)
            routing {
                staticFiles("/assets", siteDir.resolve("assets"))
                staticFiles("/content", siteDir.resolve("content"))
                staticFiles("/", siteDir)
                webSocket("/livereload") {
                    sessions.add(this)
                    try {
                        for (frame in incoming) {
                            // Keep alive or handle messages if needed
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        // Connection closed
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        sessions.remove(this)
                    }
                }
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
        println("Preview server started at http://localhost:$port")
    }

    suspend fun reload() {
        println("Sending reload signal to ${sessions.size} clients")
        val currentSessions = sessions.toList()
        currentSessions.forEach { session ->
            try {
                session.send(Frame.Text("reload"))
            } catch (e: Exception) {
                // Ignore failed sends
                sessions.remove(session)
            }
        }
    }
}
