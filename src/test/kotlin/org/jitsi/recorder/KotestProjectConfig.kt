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

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.matchers.shouldBe
import org.jitsi.metaconfig.MetaconfigSettings

class KotestProjectConfig : AbstractProjectConfig() {
    override suspend fun beforeProject() = super.beforeProject().also {
        // The only purpose of config caching is performance. We always want caching disabled in tests (so we can
        // freely modify the config without affecting other tests executing afterwards).
        MetaconfigSettings.cacheEnabled = false
    }
    override suspend fun afterProject() = super.afterProject().also {
        RecorderMetrics.instance.queueExceptions.get() shouldBe 0
        RecorderMetrics.instance.queueEventsDropped.get() shouldBe 0
    }
}
