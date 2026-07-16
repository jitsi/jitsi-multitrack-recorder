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
import org.ebml.EBMLReader
import org.ebml.Element
import org.ebml.MasterElement
import org.ebml.io.DataSource
import org.ebml.io.FileDataSource
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.Media
import org.jitsi.mediajson.MediaEvent
import org.jitsi.utils.logging2.createLogger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaJsonMkaRecorderRaceTest : ShouldSpec() {
    init {
        should("drain an active event before closing the MKA writer") {
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
                input.forEach(recorder::addEvent)

                val sizeBeforeGap = Files.size(recording)
                executor.runAsynchronously = true
                recorder.addEvent(eventWithLongGap)
                waitForFileGrowth(recording, sizeBeforeGap)
                val stopFailure = runCatching { recorder.stop() }.exceptionOrNull()

                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS) shouldBe true

                val baselineProbe = ffprobe(baseline)
                val recordingProbe = ffprobe(recording)
                if (baselineProbe != null && recordingProbe != null) {
                    recordingProbe.duration shouldBe baselineProbe.duration
                }

                stopFailure shouldBe null
                countMkaElements(recording) { it.elementType.name == "SimpleBlock" } shouldBe expectedBlocks
            } finally {
                executor.shutdownNow()
                TaskPools.ioPool = originalIoPool
            }
        }
    }

    private fun recordSynchronously(events: List<Event>): Path {
        val directory = Files.createTempDirectory("MediaJsonMkaRecorderBaseline")
        val recorder = MediaJsonMkaRecorder(directory.toFile(), createLogger())
        events.forEach(recorder::addEvent)
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

    /**
     * Run ffprobe when it is available. The EBML assertions below remain the portable CI fallback.
     */
    private fun ffprobe(recording: Path): FfprobeResult? = try {
        val process = ProcessBuilder(
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=nw=1:nk=1",
            recording.toString()
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        val result = FfprobeResult(
            exitCode,
            output,
            output.lineSequence().mapNotNull(String::toDoubleOrNull).singleOrNull()
        )
        println("ffprobe ${recording.fileName}: exit=${result.exitCode}, output=${result.output.replace('\n', '|')}")
        result
    } catch (_: IOException) {
        println("ffprobe is not installed; using the built-in EBML validation only")
        null
    }

    private fun countMkaElements(path: Path, match: (Element) -> Boolean): Int {
        val dataSource = FileDataSource(path.toString())
        val reader = EBMLReader(dataSource)
        var element = reader.readNextElement()
        var count = 0
        while (element != null) {
            if (match(element)) count++
            count += countChildren(element, dataSource, reader, match)
            element.skipData(dataSource)
            element = reader.readNextElement()
        }
        return count
    }

    private fun countChildren(
        element: Element,
        dataSource: DataSource,
        reader: EBMLReader,
        match: (Element) -> Boolean
    ): Int {
        if (element !is MasterElement) return 0

        var count = 0
        var child = element.readNextChild(reader)
        while (child != null) {
            if (match(child)) count++
            count += countChildren(child, dataSource, reader, match)
            child.skipData(dataSource)
            child = element.readNextChild(reader)
        }
        return count
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

    private data class FfprobeResult(val exitCode: Int, val output: String, val duration: Double?)
}
