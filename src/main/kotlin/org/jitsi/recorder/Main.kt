package org.jitsi.recorder

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.prometheus.client.exporter.common.TextFormat
import org.jitsi.mediajson.Event
import org.jitsi.utils.logging2.LoggerImpl
import java.io.File
import java.time.Duration
import org.jitsi.recorder.RecorderMetrics.Companion.instance as metrics

val logger = LoggerImpl("org.jitsi.recorder.Main")

fun Application.module() {
    install(WebSockets) {
        logger.info("Installing web sockets")
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        logger.info("Installing routing2?")
        get("/") {
            logger.info("http Request")
            call.respondText("Hello, world!")
        }
        get("/metrics") {
            val accept = call.request.headers["Accept"]
            logger.info("metrics request accept=$accept")
            when {
                accept?.startsWith("application/openmetrics-text") == true ->
                    call.respondText(
                        metrics.getPrometheusMetrics(TextFormat.CONTENT_TYPE_OPENMETRICS_100),
                        contentType = ContentType.parse(TextFormat.CONTENT_TYPE_OPENMETRICS_100)
                    )

                accept?.startsWith("text/plain") == true ->
                    call.respondText(
                        metrics.getPrometheusMetrics(TextFormat.CONTENT_TYPE_004),
                        contentType = ContentType.parse(TextFormat.CONTENT_TYPE_004)
                    )

                else ->
                    call.respondText(
                        metrics.jsonString,
                        contentType = ContentType.parse("application/json")
                    )
            }
        }

        webSocket("/record/{meetingid}") {
            val meetingId = call.parameters["meetingid"] ?: return@webSocket
            logger.info("New recording session for meetingId $meetingId")
            val session = RecordingSession(meetingId)
            //send(Frame.Text("Please enter your name"))
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        session.processText(frame.readText())
                    }
                    is Frame.Close -> {
                        session.stop()
                    }
                    else -> logger.info("Received frame: ${frame::class.simpleName}")
                }
            }
            logger.info("Completed")
            session.stop()
        }
    }
}

fun main(args: Array<String>) {
    logger.info("Running main, port=${Config.port}, recordingDirectory=${Config.recordingDirectory}")
    metrics.sessionsStarted.inc()
    embeddedServer(Netty, port = Config.port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

class RecordingSession(val meetingId: String) {
    val mediaJsonRecorder = MediaJsonRecorder(selectDirectory(meetingId))
    fun processText(text: String) {
        try {
            mediaJsonRecorder.queue.add(Event.parse(text))
        } catch(e: Throwable) {
            logger.error("Error", e)
        }
    }
    fun stop() = mediaJsonRecorder.stop().also {
        logger.warn("XXX stopping")
    }

    fun selectDirectory(meetingId: String): File {
        val path = "${Config.recordingDirectory}/$meetingId"
        val file = File(path)
        if (!file.exists()) {
            file.mkdirs()
            return file
        } else {
            // TODO create a new one
            throw FileAlreadyExistsException(file, null, "Directory for meetingId $meetingId already exists")
        }
    }
}
