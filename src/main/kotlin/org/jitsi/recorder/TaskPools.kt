package org.jitsi.recorder

import org.jitsi.utils.concurrent.CustomizableThreadFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object TaskPools {
    /**
     * A global executor service which can be used for non-CPU-intensive tasks.
     */
    val IO_POOL: ExecutorService =
        Executors.newCachedThreadPool(CustomizableThreadFactory("Global IO pool", false))
}