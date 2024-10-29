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

import org.ebml.io.FileDataWriter
import org.ebml.matroska.MatroskaFileFrame
import org.ebml.matroska.MatroskaFileSimpleTag
import org.ebml.matroska.MatroskaFileTagEntry
import org.ebml.matroska.MatroskaFileTrack
import org.ebml.matroska.MatroskaFileTrack.TrackType
import org.ebml.matroska.MatroskaFileWriter
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import java.io.File
import java.nio.ByteBuffer

class MkaRecorder(directory: File, parentLogger: Logger = LoggerImpl("MkaRecorder")) {
    private val logger: Logger = parentLogger.createChildLogger(this.javaClass.name)
    private val destination: File = File(directory, "recording.mka").apply {
        logger.info("Writing to $this")
    }

    private val ioDW = FileDataWriter(destination.path)
    private val writer: MatroskaFileWriter = MatroskaFileWriter(ioDW)
    private val tracks = mutableMapOf<String, MatroskaFileTrack>()
    private var initialTimestampMs = -1L

    fun startTrack(name: String, endpointId: String? = null) {
        logger.info("Starting new track $name, endpointId=$endpointId")
        val track = MatroskaFileTrack().apply {
            trackNo = tracks.size + 1
            trackUID = trackNo.toLong()
            setName(name)
            trackType = TrackType.AUDIO
            codecID = "A_OPUS"
            defaultDuration = 20_000_000
            isFlagLacing = false
            audio = MatroskaFileTrack.MatroskaAudioTrack().apply {
                channels = 1
                samplingFrequency = 48000F
                outputSamplingFrequency = 48000F
            }
            seekPreroll = 80_000_000
        }
        tracks[name] = track
        writer.addTrack(track)

        if (endpointId != null) {
            writer.addTag(
                MatroskaFileTagEntry().apply {
                    trackUID.add(track.trackUID)
                    simpleTags.add(
                        MatroskaFileSimpleTag().apply {
                            this.name = "endpoint_id"
                            value = endpointId
                        }
                    )
                }
            )
        }
    }

    fun setTrackChannels(trackName: String, numChannels: Int) {
        val track = tracks[trackName] ?: throw Exception("Track not started")
        track.audio.channels = numChannels.toShort()
    }

    fun addFrame(trackName: String, timestampRtp: Long, payload: ByteArray) {
        val track = tracks[trackName] ?: throw Exception("Track not started")
        val frame = MatroskaFileFrame()
        frame.data = ByteBuffer.wrap(payload)
        frame.trackNo = track.trackNo
        if (initialTimestampMs == -1L) {
            frame.timecode = 0
            initialTimestampMs = timestampRtp / 48
        } else {
            frame.timecode = (timestampRtp / 48) - initialTimestampMs
        }
        logger.debug("Adding frame to track ${track.trackNo} at timecode ${frame.timecode}")
        writer.addFrame(frame)
    }

    fun close() {
        writer.close()
        ioDW.close()
    }
}
