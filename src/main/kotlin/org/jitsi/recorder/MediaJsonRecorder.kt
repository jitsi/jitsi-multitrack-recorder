package org.jitsi.recorder

import kotlin.io.encoding.Base64
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.StartEvent
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.queue.PacketQueue
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.Executors
import kotlin.io.encoding.ExperimentalEncodingApi

class MediaJsonRecorder(directory: File) {
    private val logger = createLogger()
    init {
        logger.info("Will record in $directory")
    }

    private val mkaRecorder = MkaRecorder(directory)
    val queue = EventQueue {
        handleEvent(it)
        true
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun handleEvent(event: Event) {
        when(event) {
            // split, buffer, generate silence. thread model? queue. metrics?
            is StartEvent -> {
                logger.info("Start new stream: $event")
                mkaRecorder.startTrack(event.start.tag)
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

    fun stop() {
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