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
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.StartEvent
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class MkaRecorderTest : ShouldSpec() {
    init {
        val sample = "/opus-sample3.json"
        val input = javaClass.getResource(sample)?.readText()?.lines()?.dropLast(1) ?: fail("Can not read $sample")
        val objectMapper = jacksonObjectMapper()
        val inputJson: List<Event> = input.map { objectMapper.readValue(it, Event::class.java) }
        println("Parsed ${inputJson.size} events")

        context("Recording") {
            val directory = Files.createTempDirectory("MkaRecorderTest").toFile()
            println("Recording to $directory")
            val recorder = MkaRecorder(directory)

            inputJson.forEach {
                if (it is StartEvent) {
                    println("Start new track: $it")
                    recorder.startTrack(it.start.tag)
                } else if (it is MediaEvent) {
                    recorder.addFrame(it.media.tag, it.media.timestamp, Base64.decode(it.media.payload))
                }
            }

            recorder.close()
        }
    }
}
