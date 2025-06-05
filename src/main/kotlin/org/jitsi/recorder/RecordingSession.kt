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

import org.jitsi.mediajson.Event
import org.jitsi.utils.logging2.createLogger
import java.io.File
import org.jitsi.recorder.RecorderMetrics.Companion.instance as metrics

class RecordingSession(private val meetingId: String) {
    private val logger = createLogger().apply { addContext("meetingId", meetingId) }
    private val directory = selectDirectory(meetingId)

    init {
        metrics.sessionsStarted.inc()
        metrics.currentSessions.inc()
    }

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

    fun stop() {
        logger.info("Stopping")
        mediaJsonRecorder.stop()
        metrics.currentSessions.dec()
        finalize()
    }

    private fun finalize() {
        if (!Config.finalizeScript.isNullOrBlank()) {
            logger.info("Running finalize script")
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

            process.waitFor().let {
                if (it != 0) {
                    metrics.finalizeErrors.inc()
                    logger.warn("Error from finalize: $it")
                } else {
                    logger.info("Finalize script completed successfully")
                }
            }
        }
    }

    private fun selectDirectory(meetingId: String): File {
        var suffix = ""
        var counter = 1
        var file: File

        do {
            val path = "${Config.recordingDirectory}/$meetingId$suffix"
            file = File(path)

            if (!file.exists()) {
                file.mkdirs()
                return file
            }

            suffix = "-$counter"
            counter++
        } while (counter < 100)

        throw RuntimeException("Failed to create directory for meetingId $meetingId after 100 attempts")
    }
}
