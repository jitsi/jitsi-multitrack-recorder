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
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Record MediaJson events into a JSON file.
 */
class MediaJsonJsonRecorder(directory: File) : MediaJsonRecorder() {
    private val file: File = File(directory, "recording.json")
    private val writer: BufferedWriter = BufferedWriter(FileWriter(file, true))

    override fun addEvent(event: Event) {
        writer.write(event.toJson())
        writer.newLine()
        writer.flush()
    }

    override fun stop() {
        writer.close()
    }
}
