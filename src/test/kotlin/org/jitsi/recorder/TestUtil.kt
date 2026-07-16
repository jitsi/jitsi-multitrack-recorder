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

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.fail
import org.ebml.EBMLReader
import org.ebml.Element
import org.ebml.MasterElement
import org.ebml.io.DataSource
import org.ebml.io.FileDataSource
import org.jitsi.config.TypesafeConfigSource
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal fun setupInPlaceIoPool() {
    TaskPools.ioPool = object : ExecutorService {
        override fun execute(command: Runnable) = command.run()
        override fun <T : Any?> submit(task: Callable<T>): Future<T> {
            task.call()
            return CompletableFuture.completedFuture(null)
        }
        override fun <T : Any?> submit(task: Runnable, result: T): Future<T> {
            task.run()
            return CompletableFuture.completedFuture(result)
        }
        override fun submit(task: Runnable): Future<*> {
            task.run()
            return CompletableFuture.completedFuture(null)
        }

        override fun shutdown() = TODO("Not yet implemented")
        override fun shutdownNow(): MutableList<Runnable> = TODO("Not yet implemented")
        override fun isShutdown(): Boolean = TODO("Not yet implemented")
        override fun isTerminated(): Boolean = TODO("Not yet implemented")
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = TODO("Not yet implemented")
        override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
            TODO("Not yet implemented")
        }
        override fun <T : Any?> invokeAll(
            tasks: MutableCollection<out Callable<T>>,
            timeout: Long,
            unit: TimeUnit
        ): MutableList<Future<T>> = TODO("Not yet implemented")
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T = TODO("Not yet implemented")
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): T =
            TODO("Not yet implemented")
    }
}

suspend fun withConfig(config: String, block: suspend () -> Unit) {
    val originalConfigSource = Config.configSource.innerSource

    Config.configSource.innerSource = TypesafeConfigSource(
        "custom config",
        ConfigFactory.parseString(config).run { withFallback(ConfigFactory.load()) }
    )
    try {
        block()
    } finally {
        Config.configSource.innerSource = originalConfigSource
    }
}

/** Count all EBML elements in an MKA file that match [match]. */
internal fun countMkaElements(path: Path, match: (Element) -> Boolean): Int {
    val dataSource = FileDataSource(path.toString())
    val reader = EBMLReader(dataSource)
    var element = reader.readNextElement()
    var count = 0
    while (element != null) {
        if (match(element)) count++
        count += countMkaChildren(element, dataSource, reader, match)
        element.skipData(dataSource)
        element = reader.readNextElement()
    }
    return count
}

private fun countMkaChildren(
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
        count += countMkaChildren(child, dataSource, reader, match)
        child.skipData(dataSource)
        child = element.readNextChild(reader)
    }
    return count
}

/**
 * Validate an MKA with ffprobe when it is installed and return its duration.
 *
 * A null result means ffprobe is unavailable. Tests must retain portable EBML assertions as their primary check.
 */
internal fun playableDurationWithFfprobe(recording: Path): Double? {
    val process = try {
        ProcessBuilder(
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=nw=1:nk=1",
            recording.toString()
        ).redirectErrorStream(true).start()
    } catch (_: IOException) {
        return null
    }

    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        fail("ffprobe timed out while reading $recording")
    }

    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.exitValue() != 0) {
        fail("ffprobe failed to read $recording: $output")
    }
    return output.lineSequence().mapNotNull(String::toDoubleOrNull).singleOrNull()
        ?: fail("ffprobe did not report a duration for $recording: $output")
}
