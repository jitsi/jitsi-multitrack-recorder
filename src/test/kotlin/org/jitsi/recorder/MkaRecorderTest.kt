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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.fail
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.ebml.EBMLReader
import org.ebml.Element
import org.ebml.MasterElement
import org.ebml.io.DataSource
import org.ebml.io.FileDataSource
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.StartEvent
import org.jitsi.utils.logging2.createLogger
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class MkaRecorderTest : ShouldSpec() {
    val logger = createLogger()

    init {
        val debug = false
        val sample = "/opus-stereo.json"
        val input = javaClass.getResource(sample)?.readText()?.lines()?.dropLast(1) ?: fail("Can not read $sample")
        val objectMapper = jacksonObjectMapper()
        val inputJson: List<Event> = input.map { objectMapper.readValue(it, Event::class.java) }
        logger.info("Parsed ${inputJson.size} events")

        /**
         * This tests the MkaRecorder by recording a sample of Opus packets.
         */
        context("Record using MkaRecorder directly") {
            val directory = Files.createTempDirectory("MkaRecorderTest").toFile()
            val mkaFile = "$directory/recording.mka"
            val recorder = MkaRecorder(directory)

            inputJson.forEach {
                if (it is StartEvent) {
                    logger.info("Starting new track: $it")
                    recorder.startTrack(it.start.tag)
                } else if (it is MediaEvent) {
                    recorder.addFrame(it.media.tag, it.media.timestamp, Base64.decode(it.media.payload))
                }
            }
            recorder.close()
            logger.info("Recording completed.")

            logger.info("Total EBML elements: ${traverseMka(mkaFile) { _ -> true } }")

            // Expect as many SimpleBlock elements as packets in the sample.
            traverseMka(mkaFile) { element -> element.elementType.name == "SimpleBlock" } shouldBe
                inputJson.count { it is MediaEvent }

            if (debug) {
                traverseMka(mkaFile) { element, level ->
                    logger.info("${"  ".repeat(level)}${element.elementType.name}")
                    true
                }
            }
        }
        context("Record using MediaJsonMkaRecorder") {
            setupInPlaceIoPool()
            val directory = Files.createTempDirectory("MediaJsonMkaRecorderTest").toFile()
            val mkaFile = "$directory/recording.mka"
            val recorder = MediaJsonMkaRecorder(directory, logger)

            inputJson.forEach { recorder.addEvent(it) }
            recorder.stop()
            logger.info("Recording completed.")

            logger.info("Total EBML elements: ${traverseMka(mkaFile) { _ -> true } }")

            // Expect as many SimpleBlock elements as packets in the sample.
            traverseMka(mkaFile) { element -> element.elementType.name == "SimpleBlock" } shouldBe
                inputJson.count { it is MediaEvent }

            if (debug) {
                traverseMka(mkaFile) { element, level ->
                    logger.info("${"  ".repeat(level)}${element.elementType.name}")
                    true
                }
            }
        }
    }

    /** Traverse the MKA file and count elements that match the given predicate. */
    private fun traverseMka(path: String, match: (Element) -> Boolean) =
        traverseMka(path) { element, _ -> match(element) }

    private fun traverseMka(path: String, match: (Element, Int) -> Boolean): Int {
        val ioDS = FileDataSource(path)
        val reader = EBMLReader(ioDS)
        var level0 = reader.readNextElement()
        var count = 0
        while (level0 != null) {
            if (match(level0, 0)) count++
            count += traverseElement(level0, ioDS, reader, 0, match)
            level0.skipData(ioDS)
            level0 = reader.readNextElement()
        }
        return count
    }

    private fun traverseElement(
        element: Element,
        ioDS: DataSource,
        reader: EBMLReader,
        level: Int,
        match: (Element, Int) -> Boolean = { _, _ -> true }
    ): Int {
        var count = 0
        if (match(element, level)) count++

        val elemLevel = element.elementType.level
        if (elemLevel != -1) {
            check(level.toLong() == elemLevel.toLong())
        }
        if (element is MasterElement) {
            var levelNPlusOne = element.readNextChild(reader)
            while (levelNPlusOne != null) {
                count += traverseElement(levelNPlusOne, ioDS, reader, level + 1, match)
                levelNPlusOne.skipData(ioDS)
                levelNPlusOne = element.readNextChild(reader)
            }
        }

        return count
    }
}
