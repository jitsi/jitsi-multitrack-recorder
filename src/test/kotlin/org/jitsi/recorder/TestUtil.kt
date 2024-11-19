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
import org.jitsi.config.TypesafeConfigSource
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

fun withConfig(config: String, block: () -> Unit) {
    val originalConfigSource = Config.configSource.innerSource

    Config.configSource.innerSource = TypesafeConfigSource(
        "custom config",
        ConfigFactory.parseString(config).run { withFallback(ConfigFactory.load()) }
    )
    block()
    Config.configSource.innerSource = originalConfigSource
}
