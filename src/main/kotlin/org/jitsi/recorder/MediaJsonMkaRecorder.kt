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
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.StartEvent
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.queue.PacketQueue
import java.io.File
import java.time.Clock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Record MediaJson events into a Matroska file.
 */
class MediaJsonMkaRecorder(directory: File) : MediaJsonRecorder() {
    private val logger = createLogger()

    init {
        logger.info("Will record in $directory")
    }

    private val mkaRecorder = MkaRecorder(directory)
    val queue = EventQueue {
        handleEvent(it)
        true
    }

    override fun addEvent(event: Event) {
        queue.add(event)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun handleEvent(event: Event) {
        when (event) {
            // split, buffer, generate silence. thread model? queue. metrics?
            is StartEvent -> {
                logger.info("Start new stream: $event")
                mkaRecorder.startTrack(event.start.tag, event.start.customParameters?.endpointId)
            }

            is MediaEvent -> {
                mkaRecorder.addFrame(
                    event.media.tag,
                    event.media.timestamp,
                    Base64.decode(event.media.payload)
                )
            }
        }
    }

    override fun stop() {
        logger.info("Stopping.")
        mkaRecorder.close()
    }
}

class EventQueue(packetHandler: (Event) -> Boolean) : PacketQueue<Event>(
    100,
    false,
    "id",
    packetHandler,
    TaskPools.IO_POOL,
    Clock.systemUTC()
)
