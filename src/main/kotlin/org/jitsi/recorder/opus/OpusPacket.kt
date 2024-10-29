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

import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Represent an Opus packet. Supports reading some of the fields, and generating packets for Packet Loss Concealment.
 *
 * All Opus packets start with a TOC byte:
 *  0 1 2 3 4 5 6 7
 *  +-+-+-+-+-+-+-+-+
 *  | config  |s| c |
 *  +-+-+-+-+-+-+-+-+
 *
 * Code-3 packets (c=3) have an additional "frame count" byte following the TOC, where M encodes the number of frames:
 * 0 1 2 3 4 5 6 7
 * +-+-+-+-+-+-+-+-+
 * |v|p|     M     |
 * +-+-+-+-+-+-+-+-+
 *
 * See https://tools.ietf.org/html/rfc6716 for the full format specification.
 * We don't support extracting any of the frames, or even their encoded lengths from a packet.
 */
@JvmInline
value class OpusPacket(private val data: ByteArray) {
    init {
        require(data.isNotEmpty()) { "OpusFrame must have at least one byte (TOC)." }
        if (toc().code() == 3) {
            require(data.size >= 2) { "OpusFrame code-3 packets must have at least 2 bytes." }
        }
    }

    fun toc(): OpusToc = OpusToc(data.first())

    /** The duration of the packet (which may contain multiple frames) in milliseconds. */
    fun duration(): Double = toc().frameDuration() * numFrames()

    /** Number of frames in the packet */
    fun numFrames(): Int = when (toc().code()) {
        0 -> 1
        1, 2 -> 2
        // The M field from the second byte.
        3 -> data[1].toInt() and 0x3f
        else -> throw IllegalArgumentException("Invalid code: ${toc().code()}")
    }

    override fun toString(): String =
        "OpusFrame(${data.size} bytes, toc=${toc()} frames=${numFrames()} duration=${duration()})"

    companion object {

        /**
         * Generates a sequence of Opus packets for Packet Loss Concealment, with a combined duration as close as
         * possible but not exceeding [durationMs]. The [toc] field should be set to the [toc] of the last packet
         * from the stream for which we're generating PLC.
         *
         * The generated packets always have the mode, bandwidth, and number of channels as [toc], while the frame
         * duration may change to a shorter one to fill in the desired duration. We first generate packets matching the
         * frame duration of [toc], and then switch to the smallest possible frame size for the rest.
         *
         * See RFC7845 (OGG/OPUS) Section 4.1 for the reasons for this choice.
         * https://datatracker.ietf.org/doc/html/rfc7845#section-4.1
         *
         * Returns the generated packets and the remaining duration that could not be filled.
         */
        fun generatePlc(
            /** The desired duration of the PLC in milliseconds. */
            durationMs: Double,
            /**
             *  The config field which specifies mode, bandwidth, and frame duration. Should be set to the config of the
             *  last seen packet from the stream.
             */
            toc: OpusToc = OpusToc.create(mode = Mode.HYBRID, frameDuration = 20.0)
        ): Pair<List<OpusPacket>, Double> {
            val preferredFrameDuration = toc.frameDuration()
            val packets: MutableList<OpusPacket> = mutableListOf()
            val minFrameSize = toc.mode().minFrameSizeMs()

            var packet: OpusPacket?
            var remaining = durationMs
            do {
                packet = when {
                    remaining >= preferredFrameDuration -> singlePlc(toc, (remaining / preferredFrameDuration).toInt())
                    remaining >= minFrameSize -> singlePlc(toc.minimizeFrameSize(), (remaining / minFrameSize).toInt())
                    else -> null
                }
                packet?.let {
                    packets.add(it)
                    remaining -= it.duration()
                }
            } while (packet != null)
            return packets to remaining
        }

        private fun singlePlc(toc: OpusToc, n: Int): OpusPacket {
            require(n > 0)
            val frameDuration = toc.frameDuration()
            if (n == 1) {
                return OpusPacket(byteArrayOf(toc.setCode(0).data))
            }
            if (n == 2) {
                return OpusPacket(byteArrayOf(toc.setCode(1).data))
            }

            // Code 3. An Opus packet is limited to encoding 120 ms of audio, no matter the frame duration.
            val m = n.coerceAtMost((120 / frameDuration).toInt())
            val tocCode3 = toc.setCode(3)
            return OpusPacket(byteArrayOf(tocCode3.data, m.toByte()))
        }
    }
}

/**
 *
 * The TOC byte of an opus packet.
 *  0 1 2 3 4 5 6 7
 *  +-+-+-+-+-+-+-+-+
 *  | config  |s| c |
 *  +-+-+-+-+-+-+-+-+
 */
@JvmInline
value class OpusToc(val data: Byte) {
    fun code(): Int = (data and 0x03.toByte()).toInt()
    fun config(): Int = (data and 0xf8.toByte()).toUByte().toInt() shr 3
    fun mode(): Mode = Mode.fromConfig(config())
    fun stereo(): Boolean = (data and 0x04.toByte()) != 0.toByte()

    /** The duration of each of the frames in the packet in milliseconds. */
    fun frameDuration(): Double {
        val config = config()
        return when (mode()) {
            Mode.SILK -> when {
                config % 4 == 0 -> 10.0
                config % 4 == 1 -> 20.0
                config % 4 == 2 -> 40.0
                else -> 60.0
            }

            Mode.HYBRID -> when {
                config % 2 == 0 -> 10.0
                else -> 20.0
            }

            Mode.CELT -> when {
                config % 4 == 0 -> 2.5
                config % 4 == 1 -> 5.0
                config % 4 == 2 -> 10.0
                else -> 20.0
            }
        }
    }

    /**
     * Return a new [OpusToc] with the stereo, code, mode and bandwidth preserved, while the frame duration is set
     * to the minimum for the given config.
     * E.g. a SILK, MB, stereo 40 ms frame TOC will be converted to a SILK, NB, stereo 10 ms frame TOC.
     */
    fun minimizeFrameSize(): OpusToc {
        val newConfig = when (config()) {
            in 0..3 -> 0
            in 4..7 -> 4
            in 8..11 -> 8
            in 12..13 -> 12
            in 14..15 -> 14
            in 16..19 -> 16
            in 20..23 -> 20
            in 24..27 -> 24
            in 28..31 -> 28
            else -> throw IllegalArgumentException("Invalid config $this")
        }
        return OpusToc(((data and 0x07.toByte()) or (newConfig shl 3).toByte()))
    }

    /** Return a new [OpusToc] with the given code and everything else preserved. */
    fun setCode(code: Int): OpusToc {
        require(code in 0..3) { "Invalid code: $code" }
        return OpusToc((data and 0xfc.toByte()) or code.toByte())
    }

    override fun toString(): String = "OpusToc(code=${code()}, config=${config()}, mode=${mode()})"

    companion object {
        fun create(
            mode: Mode = Mode.HYBRID,
            bandwidth: Int = 0,
            frameDuration: Double = 10.0,
            stereo: Boolean = false,
            code: Int = 0
        ): OpusToc {
            require(code in 0..3) { "Invalid code: $code" }
            if (bandwidth !in 0..3 || (mode == Mode.HYBRID && bandwidth !in 0..1)) {
                throw IllegalArgumentException("Invalid bandwidth: $bandwidth")
            }

            val fsIdx = when (mode) {
                Mode.SILK -> when (frameDuration) {
                    10.0 -> 0
                    20.0 -> 1
                    40.0 -> 2
                    60.0 -> 3
                    else -> throw IllegalArgumentException("Invalid frame size=$frameDuration for mode=$mode")
                }
                Mode.HYBRID -> when (frameDuration) {
                    10.0 -> 0
                    20.0 -> 1
                    else -> throw IllegalArgumentException("Invalid frame size=$frameDuration for mode=$mode")
                }
                Mode.CELT -> when (frameDuration) {
                    2.5 -> 0
                    5.0 -> 1
                    10.0 -> 2
                    20.0 -> 3
                    else -> throw IllegalArgumentException("Invalid frame size=$frameDuration for mode=$mode")
                }
            }
            val modeOffset = when (mode) {
                Mode.SILK -> 0
                Mode.HYBRID -> 12
                Mode.CELT -> 16
            }
            val frameSizes = if (mode == Mode.HYBRID) 2 else 4
            val config = modeOffset + bandwidth * frameSizes + fsIdx

            return OpusToc((config shl 3).toByte() or (if (stereo) 0x04 else 0x00).toByte() or code.toByte())
        }
    }
}

enum class Mode {
    SILK,
    HYBRID,
    CELT;

    companion object {
        fun fromConfig(config: Int): Mode = when {
            config <= 11 -> SILK
            config <= 15 -> HYBRID
            else -> CELT
        }
    }

    fun minFrameSizeMs(): Double = when (this) {
        SILK, HYBRID -> 10.0
        CELT -> 2.5
    }
}
