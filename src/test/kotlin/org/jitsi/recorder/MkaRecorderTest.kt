package org.jitsi.recorder

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.fail
import io.kotest.core.spec.style.ShouldSpec
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.StartEvent
import org.jitsi.utils.logging2.createLogger
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class RecorderTest : ShouldSpec(){
    val logger = createLogger()
    init {
        val input = javaClass.getResource("/opus-sample3.json")?.readText()?.lines()?.dropLast(1) ?: fail("Can not read opus-sample.json")
        val objectMapper = jacksonObjectMapper()
        val inputJson: List<Event> = input.map { objectMapper.readValue(it, Event::class.java) }
        logger.info("Parsed ${inputJson.size} events")

        context("Recording") {
            logger.warn("Running")
            val recorder = MkaRecorder(File.createTempFile("MkaRecorderTest", ".mka"))


            inputJson.forEach {
                if (it is StartEvent) {
                    logger.info("Start new stream: $it")
                    recorder.startTrack(it.start.tag)
                } else if (it is MediaEvent) {
                    //logger.info("Adding frame to ${it.media.tag}")
                    recorder.addFrame(it.media.tag, it.media.timestamp, Base64.decode(it.media.payload))
                }
            }

            recorder.close()
        }
    }
}