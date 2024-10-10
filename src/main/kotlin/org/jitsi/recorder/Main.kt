/*
 * Jitsi Multi Track Recorder
 *
 * Copyright @ 2024-Present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.recorder

import io.ktor.http.ContentType
import io.ktor.server.application.Application
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
import kotlin.time.Duration.Companion.seconds
import org.jitsi.recorder.RecorderMetrics.Companion.instance as metrics

private val logger = LoggerImpl("org.jitsi.recorder.Main")

fun Application.module() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
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
    logger.info("Recording format: ${Config.recordingFormat}")
    metrics.sessionsStarted.inc()
    embeddedServer(Netty, port = Config.port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

class RecordingSession(val meetingId: String) {
    private val mediaJsonRecorder = if (Config.recordingFormat == RecordingFormat.MKA) {
        MediaJsonMkaRecorder(selectDirectory(meetingId))
    } else {
        MediaJsonJsonRecorder(selectDirectory(meetingId))
    }

    fun processText(text: String) {
        try {
            mediaJsonRecorder.addEvent(Event.parse(text))
        } catch (e: Throwable) {
            logger.error("Error", e)
        }
    }

    fun stop() = mediaJsonRecorder.stop().also {
        logger.warn("Stopping")
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
