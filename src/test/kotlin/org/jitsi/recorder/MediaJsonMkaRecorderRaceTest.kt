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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.Media
import org.jitsi.mediajson.MediaEvent
import org.jitsi.utils.logging2.createLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class MediaJsonMkaRecorderRaceTest : ShouldSpec() {
    init {
        should("drain an active event before closing the MKA writer").config(timeout = 20.seconds) {
            val originalIoPool = TaskPools.ioPool
            val executor = SwitchableExecutor()
            TaskPools.ioPool = executor

            try {
                val input = javaClass.getResource("/sample-stereo.json")?.readText()?.lineSequence()?.take(3)
                    ?.map(Event::parse)?.toList() ?: fail("Can not read sample")
                val second = input.last() as MediaEvent
                val source = second.media
                val eventWithLongGap = MediaEvent(
                    second.sequenceNumber + 1,
                    Media(
                        source.tag,
                        source.chunk + 1,
                        source.timestamp + 4L * 60 * 48_000,
                        source.payload
                    )
                )

                val baseline = recordSynchronously(input + eventWithLongGap)
                val expectedBlocks = countMkaElements(baseline) { it.elementType.name == "SimpleBlock" }
                expectedBlocks shouldBeGreaterThan 1_900

                val directory = Files.createTempDirectory("MediaJsonMkaRecorderRaceTest")
                val recording = directory.resolve("recording.mka")
                val recorder = MediaJsonMkaRecorder(directory.toFile(), createLogger())
                for (event in input) {
                    recorder.addEvent(event)
                }

                val sizeBeforeGap = Files.size(recording)
                executor.runAsynchronously = true
                recorder.addEvent(eventWithLongGap)
                waitForFileGrowth(recording, sizeBeforeGap)
                val stopFailure = runCatching { recorder.stop() }.exceptionOrNull()

                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS) shouldBe true

                val baselineDuration = playableDurationWithFfprobe(baseline)
                val recordingDuration = playableDurationWithFfprobe(recording)
                if (baselineDuration != null && recordingDuration != null) {
                    recordingDuration shouldBe baselineDuration
                }

                stopFailure shouldBe null
                countMkaElements(recording) { it.elementType.name == "SimpleBlock" } shouldBe expectedBlocks
            } finally {
                executor.shutdownNow()
                TaskPools.ioPool = originalIoPool
            }
        }
    }

    private suspend fun recordSynchronously(events: List<Event>): Path {
        val directory = Files.createTempDirectory("MediaJsonMkaRecorderBaseline")
        val recorder = MediaJsonMkaRecorder(directory.toFile(), createLogger())
        for (event in events) {
            recorder.addEvent(event)
        }
        recorder.stop()
        return directory.resolve("recording.mka")
    }

    private fun waitForFileGrowth(recording: Path, initialSize: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Files.size(recording) <= initialSize) {
            if (System.nanoTime() >= deadline) fail("The asynchronous event handler did not start")
            Thread.onSpinWait()
        }
    }

    private class SwitchableExecutor : AbstractExecutorService() {
        private val delegate = Executors.newSingleThreadExecutor()

        @Volatile
        var runAsynchronously = false

        override fun execute(command: Runnable) {
            if (runAsynchronously) delegate.execute(command) else command.run()
        }

        override fun shutdown() = delegate.shutdown()

        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()

        override fun isShutdown(): Boolean = delegate.isShutdown

        override fun isTerminated(): Boolean = delegate.isTerminated

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = delegate.awaitTermination(timeout, unit)
    }
}
