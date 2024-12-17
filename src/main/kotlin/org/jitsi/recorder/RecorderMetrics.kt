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

import org.jitsi.metrics.MetricsContainer

class RecorderMetrics private constructor() : MetricsContainer(namespace = "jitsi_recorder") {
    val sessionsStarted = registerCounter("sessions_started", "Number of sessions started")
    val currentSessions = registerLongGauge("current_sessions", "Number of current sessions")
    val finalizeErrors = registerCounter("finalize_errors", "Number of finalize errors")
    val trackResets = registerCounter("track_resets", "Number of tracks reset due to long gaps")
    val recordedMilliseconds = registerDoubleGauge(
        "recorded_milliseconds",
        "Duration of tracks recorded in milliseconds"
    )
    val plcMilliseconds = registerDoubleGauge(
        "plc_milliseconds",
        "Duration of recorded packet concealment packets in milliseconds"
    )
    val queueEventsDropped = registerCounter("queue_events_dropped", "Number of events dropped")
    val queueExceptions = registerCounter("queue_exceptions", "Number of exceptions from the event queue")
    val uncaughtExceptions = registerCounter("uncaught_exceptions", "Number of uncaught exceptions")

    companion object {
        /**
         * The singleton instance of `MetricsContainer`.
         */
        @JvmStatic
        val instance = RecorderMetrics()
    }
}
