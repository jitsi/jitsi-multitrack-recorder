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
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.ebml.EBMLReader
import org.ebml.Element
import org.ebml.MasterElement
import org.ebml.io.DataSource
import org.ebml.io.FileDataSource
import org.jitsi.config.withNewConfig
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.StartEvent
import org.jitsi.utils.logging2.createLogger
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalEncodingApi::class)
class MkaRecorderTest : ShouldSpec() {
    private val logger = createLogger()

    val debug = false
    val loss = 0
    val objectMapper = jacksonObjectMapper()

    init {
        setupInPlaceIoPool()
        /**
         * This tests the MkaRecorder by recording a sample of Opus packets.
         */
        context("Record using MkaRecorder directly") {
            val directory = Files.createTempDirectory("MkaRecorderTest").toFile()
            val mkaFile = "$directory/recording.mka"
            val recorder = MkaRecorder(directory)
            runOnce(
                "/sample-stereo.json",
                mkaFile,
                {
                    when (it) {
                        is StartEvent -> recorder.startTrack(it.start.tag)
                        is MediaEvent -> {
                            recorder.addFrame(it.media.tag, it.media.timestamp, Base64.decode(it.media.payload))
                        }
                    }
                },
                { recorder.close() }
            )
        }
        context("Record using MediaJsonMkaRecorder") {
            val directory = Files.createTempDirectory("MediaJsonMkaRecorderTest").toFile()
            val mkaFile = "$directory/recording.mka"
            val recorder = MediaJsonMkaRecorder(directory, logger)
            runOnce(
                "/sample-stereo.json",
                mkaFile,
                { recorder.addEvent(it) },
                { recorder.stop() }
            )
        }
        context("Test PLC with a big gap") {
            withNewConfig("jitsi-multitrack-recorder.recording.max-gap-duration = 5 seconds") {
                Config.maxGapDuration shouldBe 5.seconds

                val directory = Files.createTempDirectory("MediaJsonMkaRecorderTest").toFile()
                val mkaFile = "$directory/recording.mka"
                val recorder = MediaJsonMkaRecorder(directory, logger)
                runOnce(
                    "/sample-gap.json",
                    mkaFile,
                    { recorder.addEvent(it) },
                    { recorder.stop() }
                )

                // The sample has 2 endpoints, but one has a gap of 10 seconds, so it should be split into 2 tracks.
                traverseMka(mkaFile) { it.elementType.name == "TrackEntry" } shouldBe 3
            }
        }
    }

    fun runOnce(sample: String, mkaFile: String, addEvent: (Event) -> Unit, close: () -> Unit) {
        val input = javaClass.getResource(sample)?.readText()?.lines()?.dropLast(1) ?: fail("Can not read $sample")
        val inputJson: List<Event> = input.map { objectMapper.readValue(it, Event::class.java) }.also {
            logger.info("Parsed ${it.size} events")
        }

        var mediaPackets = 0
        var lostPackets = 0
        logger.warn("Using ${loss * 100}% packet loss.")

        inputJson.forEach {
            if (it is MediaEvent) {
                if (Random.nextDouble() > loss) {
                    addEvent(it)
                    mediaPackets++
                } else {
                    lostPackets++
                }
            } else {
                addEvent(it)
            }
        }
        close()
        logger.info("Recording completed.")
        if (lostPackets > 0) logger.warn("Lost $lostPackets packets.")

        logger.info("Total EBML elements: ${traverseMka(mkaFile) { _ -> true } }")

        // Expect as many SimpleBlock elements as opus packets in the sample.
        traverseMka(mkaFile) { it.elementType.name == "SimpleBlock" } shouldBeGreaterThanOrEqual mediaPackets

        if (debug) {
            traverseMka2(mkaFile) { element, level ->
                logger.info("${"  ".repeat(level)}${element.elementType.name}")
                true
            }
        }
    }

    /** Traverse the MKA file and count elements that match the given predicate. */
    private fun traverseMka(path: String, match: (Element) -> Boolean) =
        traverseMka2(path) { element, _ -> match(element) }

    private fun traverseMka2(path: String, match: (Element, Int) -> Boolean): Int {
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
