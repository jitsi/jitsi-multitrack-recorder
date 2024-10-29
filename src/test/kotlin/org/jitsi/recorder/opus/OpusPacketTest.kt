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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import kotlin.experimental.or

class OpusPacketTest : ShouldSpec({
    context("OpusToc") {
        should("create a TOC correctly") {
            OpusToc.create(Mode.HYBRID, 0, 10.0, false, 0) shouldBe OpusToc((12 shl 3).toByte())
            OpusToc.create(Mode.CELT, 3, 2.5, true, 2) shouldBe OpusToc((28 shl 3).toByte() or 0x06.toByte())
            OpusToc.create(Mode.SILK, 1, 40.0, true, 1) shouldBe OpusToc((6 shl 3).toByte() or 0x05.toByte())
        }
        should("throw when params are invalid") {
            shouldThrow<Exception> {
                OpusToc.create(Mode.SILK, 10, 10.0, true, 1)
            }
            shouldThrow<Exception> {
                OpusToc.create(Mode.HYBRID, 3, 10.0, true, 1)
            }
            shouldThrow<Exception> {
                OpusToc.create(Mode.HYBRID, 1, -10.0, true, 1)
            }
            shouldThrow<Exception> {
                OpusToc.create(Mode.SILK, 1, 2.5, true, 1)
            }
            shouldThrow<Exception> {
                OpusToc.create(Mode.CELT, 1, 40.0, true, 1)
            }
            shouldThrow<Exception> {
                OpusToc.create(Mode.CELT, 1, 20.0, true, 4)
            }
        }
        should("correctly parse the mode") {
            OpusToc.create(Mode.SILK, 0, 10.0, false, 0).mode() shouldBe Mode.SILK
            OpusToc.create(Mode.HYBRID, 0, 10.0, false, 0).mode() shouldBe Mode.HYBRID
            OpusToc.create(Mode.CELT, 0, 10.0, false, 0).mode() shouldBe Mode.CELT
        }
        should("correctly parse the frame size") {
            OpusToc.create(Mode.SILK, 0, 60.0, false, 3).frameDuration() shouldBe 60.0
            OpusToc.create(Mode.HYBRID, 1, 10.0, false, 0).frameDuration() shouldBe 10.0
            OpusToc.create(Mode.CELT, 3, 2.5, true, 0).frameDuration() shouldBe 2.5
        }
        should("correctly parse the code") {
            OpusToc.create(Mode.SILK, 0, 60.0, false, 3).code() shouldBe 3
            OpusToc.create(Mode.HYBRID, 1, 10.0, false, 2).code() shouldBe 2
            OpusToc.create(Mode.CELT, 3, 2.5, true, 0).code() shouldBe 0
        }
        should("minimize the frame duration for SILK correctly") {
            val m = OpusToc.create(Mode.SILK, 0, 60.0, false, 3).minimizeFrameSize()
            m.mode() shouldBe Mode.SILK
            m.frameDuration() shouldBe 10.0
            m.code() shouldBe 3
            m.stereo() shouldBe false
        }
        should("minimize the frame duration for HYBRID correctly") {
            val m = OpusToc.create(Mode.HYBRID, 0, 10.0, true, 2)
            m.minimizeFrameSize() shouldBe m
        }
        should("minimize the frame duration for CELT correctly") {
            val m = OpusToc.create(Mode.CELT, 0, 10.0, true, 0).minimizeFrameSize()
            m.mode() shouldBe Mode.CELT
            m.frameDuration() shouldBe 2.5
            m.code() shouldBe 0
            m.stereo() shouldBe true
        }
        should("set the code correctly") {
            val toc = OpusToc.create(Mode.CELT, 0, 10.0, true, 0).setCode(2)
            toc.mode() shouldBe Mode.CELT
            toc.frameDuration() shouldBe 10.0
            toc.code() shouldBe 2
            toc.stereo() shouldBe true
        }
    }

    context("OpusPacket") {
        should("throw an exception for empty data") {
            shouldThrow<IllegalArgumentException> {
                OpusPacket(byteArrayOf())
            }
        }

        should("throw an exception for code-3 packets with less than 2 bytes") {
            shouldThrow<IllegalArgumentException> {
                OpusPacket(byteArrayOf(OpusToc.create(code = 3).data))
            }
        }

        should("correctly calculate the number of frames for different codes") {
            OpusPacket(
                byteArrayOf(OpusToc.create(code = 0).data)
            ).numFrames() shouldBe 1
            OpusPacket(
                byteArrayOf(OpusToc.create(code = 1).data)
            ).numFrames() shouldBe 2
            OpusPacket(
                byteArrayOf(OpusToc.create(code = 2).data)
            ).numFrames() shouldBe 2
            OpusPacket(
                byteArrayOf(
                    OpusToc.create(code = 3).data,
                    0x01
                )
            ).numFrames() shouldBe 1
            OpusPacket(
                byteArrayOf(
                    OpusToc.create(code = 3).data,
                    0x0f
                )
            ).numFrames() shouldBe 15
        }

        should("correctly calculate the duration of the packet") {
            OpusPacket(
                byteArrayOf(OpusToc.create(frameDuration = 10.0, code = 0).data)
            ).duration() shouldBe 10.0
            OpusPacket(
                byteArrayOf(OpusToc.create(frameDuration = 20.0, code = 1).data)
            ).duration() shouldBe 40.0
            OpusPacket(
                byteArrayOf(OpusToc.create(mode = Mode.SILK, frameDuration = 60.0, code = 2).data)
            ).duration() shouldBe 120.0
            OpusPacket(
                byteArrayOf(
                    OpusToc.create(mode = Mode.SILK, frameDuration = 40.0, code = 3).data,
                    0x01
                )
            ).duration() shouldBe 40.0
            OpusPacket(
                byteArrayOf(
                    OpusToc.create(mode = Mode.CELT, frameDuration = 2.5, code = 3).data,
                    0x0f
                )
            ).duration() shouldBe 15 * 2.5
        }
    }
})
