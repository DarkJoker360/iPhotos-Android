/*
 * Copyright 2026 Simone Esposito
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

package xyz.simoneesposito.ocloud

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.simoneesposito.ocloud.domain.model.UploadAsset
import xyz.simoneesposito.ocloud.domain.model.deduplicationKey

class UploadAssetTest {
    @Test
    fun deduplicationNeverUsesFilenameAlone() {
        val first = UploadAsset("content://one", 9, "same.jpg", 100, 500)
        val second = UploadAsset("content://two", 9, "same.jpg", 100, 500)
        val contentHash = first.copy(sha256 = "aabb")

        assertEquals("media:9:100:500", first.deduplicationKey())
        assertEquals(first.deduplicationKey(), second.deduplicationKey())
        assertEquals("sha256:aabb", contentHash.deduplicationKey())
    }
}
