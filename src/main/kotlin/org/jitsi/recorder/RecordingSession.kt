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

class RecordingSession(private val meetingId: String) {
    private val logger = createLogger().apply { addContext("meetingId", meetingId) }
    private val directory = selectDirectory(meetingId)

    init {
        RecorderMetrics.instance.sessionsStarted.inc()
        RecorderMetrics.instance.currentSessions.inc()
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

    fun stop() = mediaJsonRecorder.stop().also {
        logger.warn("Stopping")
        RecorderMetrics.instance.currentSessions.dec()
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

    private fun selectDirectory(meetingId: String): File {
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
