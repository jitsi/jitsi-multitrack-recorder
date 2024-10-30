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
import org.jitsi.recorder.opus.OpusPacket
import org.jitsi.recorder.opus.PacketLossConcealmentInserter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.queue.PacketQueue
import java.io.File
import java.time.Clock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Record MediaJson events into a Matroska file.
 */
class MediaJsonMkaRecorder(directory: File, parentLogger: Logger) : MediaJsonRecorder() {
    private val logger: Logger = parentLogger.createChildLogger(this.javaClass.name)

    private val mkaRecorder = MkaRecorder(directory, logger)
    private var lastSequenceNumber = -1

    val queue = EventQueue {
        handleEvent(it)
        true
    }
    private val trackRecorders = mutableMapOf<String, TrackRecorder>()

    init {
        logger.info("Starting a new recording.")
    }

    override fun addEvent(event: Event) {
        queue.add(event)
    }

    private fun handleEvent(event: Event) {
        val seq = event.assertFormatAndGetSeq()
        if (lastSequenceNumber != -1 && lastSequenceNumber + 1 != seq) {
            logger.warn("Missing sequence number: $lastSequenceNumber -> $seq")
        }
        lastSequenceNumber = seq

        when (event) {
            is StartEvent -> {
                logger.info("Starting new track: $event")
                if (trackRecorders.containsKey(event.start.tag)) {
                    logger.warn("Track already exists: ${event.start.tag}")
                } else {
                    trackRecorders[event.start.tag] = TrackRecorder(
                        mkaRecorder,
                        event.start.tag,
                        event.start.customParameters?.endpointId,
                        logger
                    )
                }
            }

            is MediaEvent -> {
                trackRecorders[event.media.tag]?.addPacket(event) ?: logger.warn("No track for ${event.media.tag}")
            }
        }
    }

    override fun stop() {
        logger.info("Stopping.")
        mkaRecorder.close()
        queue.close()
    }
}

private class TrackRecorder(
    private val mkaRecorder: MkaRecorder,
    private val trackName: String,
    endpointId: String?,
    parentLogger: Logger
) {
    private val logger: Logger = parentLogger.createChildLogger(this.javaClass.name).apply {
        addContext("track", trackName)
    }
    private val plcInserter = PacketLossConcealmentInserter(logger)
    private var stereo = false

    init {
        logger.info("Starting new track $trackName")
        mkaRecorder.startTrack(trackName, endpointId)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun addPacket(event: MediaEvent) {
        val payload = Base64.decode(event.media.payload)
        if (payload.isEmpty()) {
            logger.warn("Ignoring empty payload: $event")
            return
        }
        val opusPacket = OpusPacket(payload)

        if (!stereo && opusPacket.toc().stereo()) {
            stereo = true
            mkaRecorder.setTrackChannels(trackName, 2)
            logger.info("Setting stereo=true.")
        }

        plcInserter.add(opusPacket, event.media.timestamp).forEach {
            mkaRecorder.addFrame(
                trackName,
                it.timestampMs,
                it.packet.data
            )
        }
    }
}

class EventQueue(packetHandler: (Event) -> Boolean) : PacketQueue<Event>(
    100,
    false,
    "id",
    packetHandler,
    TaskPools.ioPool,
    Clock.systemUTC()
)

/**
 * Throw an exception if the event is not in the expected sequence, also extract the sequence number for convenience
 * since the field is not in the base [Event] class.
 */
private fun Event.assertFormatAndGetSeq(): Int = when (this) {
    is StartEvent -> {
        if (start.mediaFormat.encoding != "opus") {
            throw IllegalArgumentException("Unsupported media format: ${start.mediaFormat.encoding}")
        }
        if (start.mediaFormat.sampleRate != 48000) {
            throw IllegalArgumentException("Unsupported sample rate: ${start.mediaFormat.sampleRate}")
        }
        sequenceNumber
    }
    is MediaEvent -> {
        sequenceNumber
    }
}
