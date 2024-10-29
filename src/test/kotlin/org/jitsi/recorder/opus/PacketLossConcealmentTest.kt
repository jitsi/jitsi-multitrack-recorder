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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class PacketLossConcealmentTest : ShouldSpec({
    context("Generating packets for loss concealment") {
        context("When the duration is a multiple of the frame duration") {
            context("With duration 0") {
                val (packets, remaining) = OpusPacket.generatePlc(0.0, OpusToc.create())
                packets.shouldBeEmpty()
                remaining shouldBe 0.0
            }
            context("When it fits in a single packet") {
                val toc = OpusToc.create(mode = Mode.HYBRID, frameDuration = 20.0)
                val (packets, remaining) = OpusPacket.generatePlc(60.0, toc)
                packets.map { it.duration() } shouldBe listOf(60.0)
                packets.map { it.numFrames() } shouldBe listOf(3)
                packets.map { it.toc().code() } shouldBe listOf(3)
                packets.forEach { it.toc().config() shouldBe toc.config() }
                packets.forEach { it.toc().stereo() shouldBe false }
                remaining shouldBe 0.0
            }
            context("When it requires multiple packets") {
                val toc = OpusToc.create(mode = Mode.HYBRID, frameDuration = 20.0, stereo = true)
                val (packets, remaining) = OpusPacket.generatePlc(260.0, toc)
                packets.map { it.duration() } shouldBe listOf(120.0, 120.0, 20.0)
                packets.map { it.numFrames() } shouldBe listOf(6, 6, 1)
                packets.map { it.toc().code() } shouldBe listOf(3, 3, 0)
                packets.forEach { it.toc().config() shouldBe toc.config() }
                packets.forEach { it.toc().stereo() shouldBe true }
                remaining shouldBe 0.0
            }
            context("When it requires multiple packets (with smaller frame duration)") {
                val toc = OpusToc.create(mode = Mode.CELT, frameDuration = 5.0)
                val (packets, remaining) = OpusPacket.generatePlc(260.0, toc)
                packets.map { it.duration() } shouldBe listOf(120.0, 120.0, 20.0)
                packets.map { it.numFrames() } shouldBe listOf(24, 24, 4)
                packets.map { it.toc().code() } shouldBe listOf(3, 3, 3)
                packets.forEach { it.toc().config() shouldBe toc.config() }
                packets.forEach { it.toc().stereo() shouldBe false }
                remaining shouldBe 0.0
            }
        }
    }
    context("When the duration is not a multiple of the frame duration, but the remainder can be filled") {
        context("With frame duration 20") {
            val toc = OpusToc.create(mode = Mode.HYBRID, frameDuration = 20.0, stereo = true)
            val (packets, remaining) = OpusPacket.generatePlc(270.0, toc)
            packets.map { it.duration() } shouldBe listOf(120.0, 120.0, 20.0, 10.0)
            packets.map { it.numFrames() } shouldBe listOf(6, 6, 1, 1)
            packets.map { it.toc().code() } shouldBe listOf(3, 3, 0, 0)
            packets.forEach { it.toc().stereo() shouldBe true }
            packets.map { it.toc().config() } shouldBe listOf(
                toc.config(),
                toc.config(),
                toc.config(),
                OpusToc.create(mode = Mode.HYBRID, frameDuration = 10.0, stereo = true).config()
            )
            remaining shouldBe 0.0
        }
        context("With CELT and frame duration 10") {
            val toc = OpusToc.create(mode = Mode.CELT, frameDuration = 10.0)
            val (packets, remaining) = OpusPacket.generatePlc(247.5, toc)
            packets.map { it.duration() } shouldBe listOf(120.0, 120.0, 7.5)
            packets.map { it.numFrames() } shouldBe listOf(12, 12, 3)
            packets.map { it.toc().code() } shouldBe listOf(3, 3, 3)
            packets.forEach { it.toc().stereo() shouldBe false }
            packets.map { it.toc().config() } shouldBe listOf(
                toc.config(),
                toc.config(),
                OpusToc.create(mode = Mode.CELT, frameDuration = 2.5).config()
            )

            remaining shouldBe 0.0
        }
    }
    context("When the duration can not be filled exactly") {
        context("When the duration is smaller than the min frame size for the mode (SILK)") {
            val toc = OpusToc.create(mode = Mode.SILK, frameDuration = 20.0)
            val (packets, remaining) = OpusPacket.generatePlc(5.0, toc)
            packets.shouldBeEmpty()
            remaining shouldBe 5.0
        }
        context("When the duration is smaller than the min frame size for the mode (CELT)") {
            val toc = OpusToc.create(mode = Mode.CELT, frameDuration = 10.0)
            val (packets, remaining) = OpusPacket.generatePlc(2.4, toc)
            packets.shouldBeEmpty()
            remaining shouldBe 2.4
        }
        context("When multiple packets are required (HYBRID)") {
            val toc = OpusToc.create(mode = Mode.HYBRID, frameDuration = 20.0)
            val (packets, remaining) = OpusPacket.generatePlc(275.0, toc)
            packets.map { it.duration() } shouldBe listOf(120.0, 120.0, 20.0, 10.0)
            packets.map { it.numFrames() } shouldBe listOf(6, 6, 1, 1)
            packets.map { it.toc().code() } shouldBe listOf(3, 3, 0, 0)
            packets.forEach { it.toc().stereo() shouldBe false }
            packets.map { it.toc().config() } shouldBe listOf(
                toc.config(),
                toc.config(),
                toc.config(),
                OpusToc.create(mode = Mode.HYBRID, frameDuration = 10.0, stereo = true).config()
            )
            remaining shouldBe 5.0
        }
        context("When multiple packets are required (CELT)") {
            val toc = OpusToc.create(mode = Mode.CELT, frameDuration = 20.0, stereo = true)
            val (packets, remaining) = OpusPacket.generatePlc(278.0, toc)
            packets.map { it.duration() } shouldBe listOf(120.0, 120.0, 20.0, 17.5)
            packets.map { it.numFrames() } shouldBe listOf(6, 6, 1, 7)
            packets.map { it.toc().code() } shouldBe listOf(3, 3, 0, 3)
            packets.forEach { it.toc().stereo() shouldBe true }
            packets.map { it.toc().config() } shouldBe listOf(
                toc.config(),
                toc.config(),
                toc.config(),
                OpusToc.create(mode = Mode.CELT, frameDuration = 2.5, stereo = true).config()
            )
            remaining shouldBe 0.5
        }
    }
})
