package org.jitsi.recorder

import kotlin.io.encoding.Base64
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.StartEvent
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.queue.PacketQueue
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Clock
import kotlin.io.encoding.ExperimentalEncodingApi

sealed class MediaJsonRecorder(directory: File) {
    abstract fun addEvent(event: Event)
    abstract fun stop()
}


class MediaJsonJsonRecorder(directory: File) : MediaJsonRecorder(directory) {
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

enum class RecordingFormat() {
    MKA,
    JSON
}

class MediaJsonMkaRecorder(directory: File) : MediaJsonRecorder(directory) {
    private val logger = createLogger()
    init {
        logger.info("Will record in $directory")
    }

    private val mkaRecorder = MkaRecorder(directory)
    val queue = EventQueue {
        handleEvent(it)
        true
    }

    override fun addEvent(event: Event) { queue.add(event) }

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