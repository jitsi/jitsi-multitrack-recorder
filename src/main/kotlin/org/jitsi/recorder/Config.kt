package org.jitsi.recorder

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config

class Config {
    companion object {
        const val BASE = "jitsi-multitrack-recorder"
        val port: Int by config {
            "$BASE.port".from(JitsiConfig.newConfig)
        }

        val recordingDirectory: String by config {
            "$BASE.recording.directory".from(JitsiConfig.newConfig)
        }
    }
}