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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

class Config {
    companion object {
        const val BASE = "jitsi-multitrack-recorder"

        val port: Int by config {
            "$BASE.port".from(JitsiConfig.newConfig)
        }

        val recordingDirectory: String by config {
            "$BASE.recording.directory".from(JitsiConfig.newConfig)
        }

        val recordingFormat: RecordingFormat by config {
            "$BASE.recording.format".from(JitsiConfig.newConfig).convertFrom<String> {
                RecordingFormat.valueOf(it.uppercase())
            }
        }

        val maxGapDuration: Duration by config {
            "$BASE.recording.max-gap-duration".from(JitsiConfig.newConfig).convertFrom<java.time.Duration> {
                it.toKotlinDuration()
            }
        }

        val finalizeScript: String? by optionalconfig {
            "$BASE.finalize-script".from(JitsiConfig.newConfig)
        }

        override fun toString(): String = """
            port: $port
            recordingDirectory: $recordingDirectory
            recordingFormat: $recordingFormat
            maxGapDuration: $maxGapDuration
            finalizeScript: $finalizeScript
        """.trimIndent()
    }
}
