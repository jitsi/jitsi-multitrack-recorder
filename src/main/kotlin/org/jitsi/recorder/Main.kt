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
import io.ktor.http.parseHeaderValue
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
import org.jitsi.mediajson.Event
import org.jitsi.metaconfig.MetaconfigLogger
import org.jitsi.metaconfig.MetaconfigSettings
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.logging2.createLogger
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
            val (metrics, contentType) = metrics.getMetrics(
                parseHeaderValue(call.request.headers["Accept"]).sortedByDescending { it.quality }.map { it.value }
            )
            call.respondText(metrics, contentType = ContentType.parse(contentType))
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

fun main() {
    setupMetaconfigLogger()
    logger.info("Starting jitsi-multitrack-recorder with config:\n $Config")
    metrics.sessionsStarted.inc()
    embeddedServer(Netty, port = Config.port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

class RecordingSession(private val meetingId: String) {
    private val logger = createLogger().apply { addContext("meetingId", meetingId) }
    private val directory = selectDirectory(meetingId)

    private val mediaJsonRecorder = if (Config.recordingFormat == RecordingFormat.MKA) {
        MediaJsonMkaRecorder(directory, logger)
    } else {
        MediaJsonJsonRecorder(directory)
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
        if (!Config.finalizeScript.isNullOrBlank()) {
            logger.warn("Running finalize script")
            val process = ProcessBuilder(
                Config.finalizeScript,
                meetingId,
                directory.absolutePath,
                Config.recordingFormat.toString()
            ).apply {
                val logFile = File(directory, "finalize.log")
                redirectOutput(logFile)
                redirectError(logFile)
            }.start()
            val ret = process.waitFor()
            logger.warn("Finalise script returned $ret")
        }
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

/** Wire the jitsi-metaconfig logger into ours */
private fun setupMetaconfigLogger() {
    val configLogger = LoggerImpl("org.jitsi.jibri.config")
    MetaconfigSettings.logger = object : MetaconfigLogger {
        override fun debug(block: () -> String) {
            configLogger.debug(block)
        }
        override fun error(block: () -> String) {
            configLogger.error(block())
        }
        override fun warn(block: () -> String) {
            configLogger.warn(block)
        }
    }
}
