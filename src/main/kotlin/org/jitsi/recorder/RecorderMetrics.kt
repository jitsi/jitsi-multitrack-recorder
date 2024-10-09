package org.jitsi.recorder

import org.jitsi.metrics.MetricsContainer

class RecorderMetrics private constructor() : MetricsContainer(namespace = "jitsi_recorder") {
    val sessionsStarted = registerCounter("sessions_started", "Number of sessions started")
    companion object {
        /**
         * The singleton instance of `MetricsContainer`.
         */
        @JvmStatic
        val instance = RecorderMetrics()
    }
}