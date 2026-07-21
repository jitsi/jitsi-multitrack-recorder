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

import io.kotest.assertions.fail
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.Media
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.Start
import org.jitsi.mediajson.StartEvent
import org.jitsi.utils.logging2.createLogger
import java.nio.file.Files
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class MediaJsonMkaRecorderOverflowTest : ShouldSpec() {
    init {
        should("preserve every event during a high-load burst").config(timeout = 20.seconds) {
            val trackCount = 20
            val packetsPerTrack = 500
            val expectedBlocks = trackCount * packetsPerTrack

            val originalIoPool = TaskPools.ioPool
            val executor = TrackingExecutor()
            TaskPools.ioPool = executor

            try {
                val input = javaClass.getResource("/sample-stereo.json")?.readText()?.lineSequence()?.take(2)
                    ?.map(Event::parse)?.toList() ?: fail("Can not read sample")
                val sourceStart = input.first() as StartEvent
                val sourceMedia = (input.last() as MediaEvent).media
                var sequenceNumber = 1
                val trackTags = (0 until trackCount).map { "${sourceStart.start.tag}-$it" }
                val starts = trackTags.map { tag ->
                    StartEvent(
                        sequenceNumber++,
                        Start(tag, sourceStart.start.mediaFormat, sourceStart.start.customParameters)
                    )
                }
                val mediaEvents = buildList {
                    repeat(packetsPerTrack) { packet ->
                        trackTags.forEach { tag ->
                            add(
                                MediaEvent(
                                    sequenceNumber++,
                                    Media(
                                        tag,
                                        sourceMedia.chunk + packet,
                                        sourceMedia.timestamp + packet * 960L,
                                        sourceMedia.payload
                                    )
                                )
                            )
                        }
                    }
                }

                val directory = Files.createTempDirectory("MediaJsonMkaRecorderOverflowTest")
                val recording = directory.resolve("recording.mka")
                val recorder = MediaJsonMkaRecorder(directory.toFile(), createLogger())
                val droppedBefore = RecorderMetrics.instance.queueEventsDropped.get()
                for (event in starts + mediaEvents) recorder.addEvent(event)

                // Wait behind the consumer on the same serial executor. This does not slow it down; it only makes
                // stop independent from the separate close/write race covered by MediaJsonMkaRecorderRaceTest.
                executor.awaitIdle()
                val stopFailure = runCatching { recorder.stop() }.exceptionOrNull()
                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS) shouldBe true

                val dropped = RecorderMetrics.instance.queueEventsDropped.get() - droppedBefore
                val recordedBlocks = countMkaElements(recording) { it.elementType.name == "SimpleBlock" }
                playableDurationWithFfprobe(recording)

                stopFailure shouldBe null
                dropped shouldBe 0
                recordedBlocks shouldBe expectedBlocks
            } finally {
                executor.shutdownNow()
                TaskPools.ioPool = originalIoPool
            }
        }
    }

    private class TrackingExecutor : AbstractExecutorService() {
        private val delegate = Executors.newSingleThreadExecutor()

        fun awaitIdle() {
            delegate.submit {}.get(5, TimeUnit.SECONDS)
        }

        override fun execute(command: Runnable) = delegate.execute(command)

        override fun shutdown() = delegate.shutdown()

        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()

        override fun isShutdown(): Boolean = delegate.isShutdown

        override fun isTerminated(): Boolean = delegate.isTerminated

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = delegate.awaitTermination(timeout, unit)
    }
}
