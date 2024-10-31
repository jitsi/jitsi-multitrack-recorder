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
package org.jitsi.recorder.opus

import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Insert packet loss concealment packets into a stream of [OpusPacket].
 */
class PacketLossConcealmentInserter(
    private val maxGapDuration: Duration = 1.minutes,
    parentLogger: Logger = LoggerImpl("PacketLossConcealmentInserter")
) {
    private val logger: Logger = parentLogger.createChildLogger(this.javaClass.name)
    private var nextSampleTs = -1L
    private var previousToc: OpusToc? = null
    private var remainingMs = 0.0

    /**
     * Add the next opus packet. Returns a list of packets to be inserted into the stream, with their corresponding
     * timestamp. The packet itself is always included as the last element, but its timestamp may have changed.
     *
     * Note that we can't always fill the entire gap because an opus packet is at least 2.5 or 10 ms depending on the
     * mode. When we can't fill the entire gap we accumulate the remainder to be filled and shift the timestamp of the
     * actual packet by the remainder.
     */
    fun add(
        opusPacket: OpusPacket,
        /** The timestamp of the packet at 48kHz ticks (i.e. RTP) */
        ts48kHz: Long
    ): List<OpusPacketAndTimestamp> {
        val plcPackets = mutableListOf<OpusPacketAndTimestamp>()

        if (nextSampleTs < 0) {
            nextSampleTs = ts48kHz
        } else if (ts48kHz == nextSampleTs) {
            // No gap.
        } else {
            val missing = ts48kHz - nextSampleTs
            val missingMs = missing.toMs()
            if (missingMs.milliseconds > maxGapDuration) {
                throw GapTooLargeException(missingMs.milliseconds)
            }
            logger.warn("Missing $missing ticks = $missingMs ms (and $remainingMs ms remaining)")

            val plc = OpusPacket.generatePlc(missingMs + remainingMs, previousToc ?: opusPacket.toc())
            logger.warn("Filling in with ${plc.first.size} packets, remainingMs=${plc.second}")
            remainingMs = plc.second

            plc.first.forEach {
                plcPackets.add(OpusPacketAndTimestamp(it, nextSampleTs.toMs().toLong()))
                nextSampleTs += it.duration().to48kHz()
            }
        }

        previousToc = opusPacket.toc()
        nextSampleTs += opusPacket.duration().to48kHz()
        return plcPackets + OpusPacketAndTimestamp(opusPacket, nextSampleTs.toMs().toLong())
    }
}

/** Convert a duration in 48kHz ticks to milliseconds. */
fun Long.toMs(): Double = this.toDouble() / 48.0

/** Convert a duration in milliseconds to 48kHz ticks. */
fun Double.to48kHz(): Long = (this * 48).toLong()

data class OpusPacketAndTimestamp(val packet: OpusPacket, val timestampMs: Long)
class GapTooLargeException(val gapDuration: Duration) : Exception("Gap too large")
