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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.simoneesposito.ocloud.data.provider.MockPhotosProvider
import xyz.simoneesposito.ocloud.domain.model.MediaKind
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import xyz.simoneesposito.ocloud.domain.model.SyncState
import xyz.simoneesposito.ocloud.domain.model.UploadAsset

class MockPhotosProviderTest {
    @Test
    fun libraryAndAlbumPagesUseIndependentCursors() = runBlocking {
        val provider = MockPhotosProvider()
        val first = provider.getLibraryPage(cursor = null, pageSize = 5)
        val second = provider.getLibraryPage(cursor = first.nextCursor, pageSize = 5)
        val albumFirst = provider.getAlbumPage("album-city", cursor = null, pageSize = 2)

        assertEquals(5, first.assets.size)
        assertEquals(5, second.assets.size)
        assertTrue(first.assets.map { it.id }.intersect(second.assets.map { it.id }.toSet()).isEmpty())
        assertEquals(2, albumFirst.assets.size)
        assertTrue(albumFirst.assets.all { "album-city" in it.albumIds })
    }

    @Test
    fun syncReturnsChangesDeletionsAndRequestsARefreshForExpiredTokens() = runBlocking {
        val provider = MockPhotosProvider()
        val initial = provider.sync(SyncState(null))
        val oldToken = initial.nextState
        val updated = initial.changedAssets.first().copy(title = "Edited title")
        provider.modifyAsset(updated)
        provider.deleteAsset(initial.changedAssets.last().id)

        val delta = provider.sync(oldToken)
        assertEquals(listOf(updated), delta.changedAssets)
        assertEquals(setOf(initial.changedAssets.last().id), delta.deletedAssetIds)
        assertFalse(delta.fullRefreshRequired)

        val expired = provider.sync(SyncState("old-invalid-token"))
        assertTrue(expired.fullRefreshRequired)
        assertTrue(expired.fullSnapshot)
        assertTrue(expired.changedAssets.isNotEmpty())
    }

    @Test
    fun uploadsPreserveOriginalNameAndReportThatTheyAreSimulated() = runBlocking {
        val provider = MockPhotosProvider()
        val result = provider.uploadAsset(
            UploadAsset("content://photos/1", null, "capture.dng", 4096, 1_000, "abc"),
        )

        assertTrue(result.simulated)
        assertFalse(result.acceptedByApple)
        val uploaded = provider.allDemoAssets().single { it.id == result.assetId }
        assertEquals("capture.dng", uploaded.title)
        assertEquals(MediaKind.Raw, uploaded.mediaKind)
    }

    @Test
    fun generatedHundredThousandAssetLibraryPaginatesWithoutDuplicates() = runBlocking {
        val provider = MockPhotosProvider(assetCount = 100_000)
        val seen = HashSet<String>(100_000)
        var cursor: String? = null
        var total = 0
        do {
            val page = provider.getLibraryPage(cursor, pageSize = 200)
            page.assets.forEach { assertTrue("duplicate asset ${it.id}", seen.add(it.id)) }
            total += page.assets.size
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(100_000, total)
        assertEquals(100_000, seen.size)
    }
}
