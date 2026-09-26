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

package xyz.simoneesposito.ocloud.domain.provider

import xyz.simoneesposito.ocloud.domain.model.PhotoAlbum
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import xyz.simoneesposito.ocloud.domain.model.PhotoPage
import xyz.simoneesposito.ocloud.domain.model.SyncResult
import xyz.simoneesposito.ocloud.domain.model.SyncState
import xyz.simoneesposito.ocloud.domain.model.UploadAsset
import xyz.simoneesposito.ocloud.domain.model.UploadResult
import java.io.InputStream

/** UI and repositories depend on this contract, never on Apple HTTP endpoints. */
interface PhotosProvider {
    suspend fun getLibraryPage(cursor: String? = null, pageSize: Int = 60): PhotoPage
    suspend fun getAlbumPage(albumId: String, cursor: String? = null, pageSize: Int = 60): PhotoPage
    suspend fun getAlbums(): List<PhotoAlbum>
    suspend fun getThumbnail(asset: PhotoAsset): ByteArray?
    suspend fun prepareThumbnails(assets: List<PhotoAsset>) = Unit
    suspend fun getPreview(asset: PhotoAsset): ByteArray? = getThumbnail(asset)
    suspend fun downloadOriginal(asset: PhotoAsset): InputStream
    suspend fun downloadLivePhotoMotion(asset: PhotoAsset): InputStream =
        throw ProviderUnavailableException("This asset does not have a downloadable Live Photo motion component.")
    suspend fun uploadAsset(asset: UploadAsset): UploadResult
    suspend fun addAssetToAlbum(assetId: String, albumId: String)
    suspend fun sync(state: SyncState): SyncResult
}

class ProviderUnavailableException(message: String) : IllegalStateException(message)
