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

package xyz.simoneesposito.ocloud.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import xyz.simoneesposito.ocloud.R
import xyz.simoneesposito.ocloud.domain.model.MediaKind
import xyz.simoneesposito.ocloud.domain.model.PhotoAsset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewer(asset: PhotoAsset, model: OCloudViewModel, close: () -> Unit) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val isLive = asset.mediaKind == MediaKind.LivePhoto
    val isVideo = asset.mediaKind == MediaKind.Video
    val previewUri = state.videoPreviewUri.takeIf { state.videoPreviewAssetId == asset.id }
    var previewBytes by remember(asset.id) { mutableStateOf<ByteArray?>(null) }
    var previewLoading by remember(asset.id) { mutableStateOf(!isVideo) }
    var previewFailed by remember(asset.id) { mutableStateOf(false) }
    var previewRetry by remember(asset.id) { mutableIntStateOf(0) }
    var videoView by remember(asset.id) { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember(asset.id) { mutableStateOf(false) }
    var liveMotionPlaying by remember(asset.id) { mutableStateOf(false) }
    var liveHasPlayed by remember(asset.id) { mutableStateOf(false) }
    var liveFirstFrameRendered by remember(asset.id) { mutableStateOf(false) }
    var livePlaybackFailed by remember(asset.id) { mutableStateOf(false) }
    var durationMillis by remember(asset.id) { mutableIntStateOf(0) }
    var positionMillis by remember(asset.id) { mutableIntStateOf(0) }
    var imageScale by remember(asset.id) { mutableFloatStateOf(1f) }
    var imageOffset by remember(asset.id) { mutableStateOf(Offset.Zero) }
    var detailsOpen by remember(asset.id) { mutableStateOf(false) }
    var chromeVisible by remember(asset.id) { mutableStateOf(true) }
    val toggleChrome = { chromeVisible = !chromeVisible }
    val toggleZoom = {
        imageScale = if (imageScale > 1.05f) 1f else 2.5f
        if (imageScale == 1f) imageOffset = Offset.Zero
    }
    val replayLivePhoto = {
        if (isLive && previewUri != null && !liveMotionPlaying) {
            liveFirstFrameRendered = false
            livePlaybackFailed = false
            liveMotionPlaying = true
            isPlaying = false
        }
    }

    LaunchedEffect(asset.id, previewRetry) {
        if (isVideo) return@LaunchedEffect
        previewLoading = true
        previewFailed = false
        suspend fun load(): ByteArray? = try {
            model.preview(asset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        var result = load()
        if (result == null) {
            delay(700.milliseconds)
            result = load()
        }
        previewBytes = result
        previewFailed = result == null
        previewLoading = false
    }
    LaunchedEffect(asset.id, isVideo, isLive) {
        if (isVideo || isLive) model.prepareVideoPreview(asset, liveMotion = isLive)
    }
    LaunchedEffect(asset.id, previewUri, isLive) {
        if (isLive && previewUri != null) {
            liveFirstFrameRendered = false
            liveMotionPlaying = true
        }
    }
    LaunchedEffect(asset.id, previewUri, liveMotionPlaying, liveFirstFrameRendered) {
        if (!isLive || previewUri == null || !liveMotionPlaying || liveFirstFrameRendered) return@LaunchedEffect
        delay(12_000.milliseconds)
        if (liveMotionPlaying && !liveFirstFrameRendered) {
            livePlaybackFailed = true
            liveMotionPlaying = false
            isPlaying = false
        }
    }
    LaunchedEffect(videoView, isPlaying) {
        while (videoView != null) {
            if (isPlaying) positionMillis = videoView?.currentPosition?.coerceAtLeast(0) ?: 0
            delay(250.milliseconds)
        }
    }
    DisposableEffect(asset.id) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }
    val bitmap = remember(previewBytes) {
        previewBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (chromeVisible) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = close) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.close_photo), tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                formatViewerDate(asset.capturedAtMillis, context),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(asset.title, color = Color.White.copy(alpha = 0.68f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (isLive) {
                            Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(20.dp)) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.LiveTv, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(stringResource(R.string.live_badge), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                Box(
                    Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    val showMotion = previewUri != null && (isVideo || (isLive && liveMotionPlaying))
                    if (isVideo && showMotion) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext ->
                                VideoView(viewContext).also { player ->
                                    player.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                    videoView = player
                                    player.setOnPreparedListener { mediaPlayer ->
                                        durationMillis = mediaPlayer.duration.coerceAtLeast(0)
                                        mediaPlayer.isLooping = false
                                        player.start()
                                        isPlaying = true
                                    }
                                    player.setOnCompletionListener {
                                        isPlaying = false
                                        positionMillis = durationMillis
                                    }
                                    player.setOnErrorListener { _, _, _ ->
                                        isPlaying = false
                                        true
                                    }
                                    player.setVideoURI(previewUri.toUri())
                                }
                            },
                        )
                    } else if (bitmap != null) {
                        Image(
                            bitmap,
                            contentDescription = asset.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(asset.id) {
                                    var swipeDistance = 0f
                                    var didNavigate = false
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        val oldScale = imageScale
                                        val newScale = (oldScale * zoom).coerceIn(1f, 5f)
                                        if (oldScale <= 1.03f && newScale <= 1.03f && abs(pan.x) > abs(pan.y)) {
                                            swipeDistance += pan.x
                                            if (!didNavigate && abs(swipeDistance) > 92f) {
                                                model.navigatePhoto(if (swipeDistance < 0) 1 else -1)
                                                didNavigate = true
                                            }
                                        } else {
                                            val focal = centroid - Offset(size.width / 2f, size.height / 2f)
                                            val updatedOffset = ((imageOffset + pan) * zoom) + focal * (1f - zoom)
                                            val maxX = size.width * (newScale - 1f) / 2f
                                            val maxY = size.height * (newScale - 1f) / 2f
                                            imageOffset = Offset(
                                                updatedOffset.x.coerceIn(-maxX, maxX),
                                                updatedOffset.y.coerceIn(-maxY, maxY),
                                            )
                                            imageScale = newScale
                                            if (newScale <= 1.03f) imageOffset = Offset.Zero
                                        }
                                    }
                                }
                                .combinedClickable(
                                    onClick = toggleChrome,
                                    onDoubleClick = toggleZoom,
                                    onLongClick = replayLivePhoto,
                                )
                                .graphicsLayer {
                                    scaleX = imageScale
                                    scaleY = imageScale
                                    translationX = imageOffset.x
                                    translationY = imageOffset.y
                                },
                        )
                    } else if (previewLoading || state.preparingVideoPreview) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                    } else if (isVideo) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.video_could_not_load), color = Color.White.copy(alpha = 0.84f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { model.prepareVideoPreview(asset) }) { Text(stringResource(R.string.try_again), color = Color.White) }
                        }
                    } else if (previewFailed) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.preview_unavailable), color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { previewRetry++ }) { Text(stringResource(R.string.try_again), color = Color.White) }
                        }
                    }

                    if (isLive && showMotion) {
                        val stillAspectRatio = bitmap?.let { it.width.toFloat() / it.height.toFloat() }
                            ?: run {
                                val swapsDimensions = asset.orientation in setOf(5, 6, 7, 8)
                                val displayedWidth = if (swapsDimensions) asset.height else asset.width
                                val displayedHeight = if (swapsDimensions) asset.width else asset.height
                                displayedWidth.toFloat() / displayedHeight.coerceAtLeast(1).toFloat()
                            }
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (liveFirstFrameRendered) 1f else 0f),
                            factory = { viewContext ->
                                LivePhotoTextureView(
                                    context = viewContext,
                                    stillOrientation = asset.orientation,
                                    stillAspectRatio = stillAspectRatio,
                                    onPlaybackStarted = { duration ->
                                        durationMillis = duration
                                        isPlaying = true
                                    },
                                    onFirstFrame = { liveFirstFrameRendered = true },
                                    onPlaybackCompleted = {
                                        isPlaying = false
                                        positionMillis = durationMillis
                                        liveHasPlayed = true
                                        livePlaybackFailed = false
                                        liveMotionPlaying = false
                                    },
                                    onPlaybackError = {
                                        isPlaying = false
                                        livePlaybackFailed = true
                                        liveMotionPlaying = false
                                    },
                                ).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    setSource(previewUri.toUri())
                                }
                            },
                            update = { it.updateStillAspectRatio(stillAspectRatio) },
                            onRelease = { player -> player.release() },
                        )
                        if (!liveFirstFrameRendered) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).size(30.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        }
                    }

                    // Tap toggles controls, double-tap zooms, and long-press replays Live Photos.
                    if (showMotion || bitmap == null) Box(
                        Modifier.fillMaxSize().combinedClickable(
                            onClick = toggleChrome,
                            onDoubleClick = toggleZoom,
                            onLongClick = replayLivePhoto,
                        ),
                    )
                    if (isLive && (liveHasPlayed || livePlaybackFailed) && !liveMotionPlaying) {
                        Surface(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
                            color = Color.Black.copy(alpha = 0.58f),
                            shape = CircleShape,
                        ) {
                            Text(
                                stringResource(if (livePlaybackFailed) R.string.live_playback_retry else R.string.live_playback_replay),
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                if (chromeVisible) {
                    if (detailsOpen) {
                        Surface(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(stringResource(R.string.media_information), style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                DetailLine(stringResource(R.string.file_name), asset.title)
                                DetailLine(stringResource(R.string.date_and_time), formatViewerDate(asset.capturedAtMillis, context))
                                DetailLine(stringResource(R.string.media), mediaDescription(asset, context))
                                if (asset.width > 0 && asset.height > 0) {
                                    DetailLine(stringResource(R.string.dimensions), stringResource(
                                        R.string.dimensions_pixels,
                                        java.text.NumberFormat.getIntegerInstance().format(asset.width),
                                        java.text.NumberFormat.getIntegerInstance().format(asset.height),
                                    ))
                                }
                                DetailLine(stringResource(R.string.file_size), formatViewerSize(asset.sizeBytes, context))
                                asset.durationMillis?.takeIf { it > 0 }?.let { DetailLine(stringResource(R.string.duration), formatViewerDuration(it)) }
                                asset.orientation?.let { DetailLine(stringResource(R.string.orientation), formatViewerOrientation(it, context)) }
                                DetailLine(stringResource(R.string.favorite), stringResource(if (asset.isFavorite) R.string.favorite_yes else R.string.favorite_no))
                                val albumNames = state.albums.filter { it.id in asset.albumIds }
                                    .map { localizedPhotoAlbumTitle(it.title, context) }.distinct()
                                if (albumNames.isNotEmpty()) DetailLine(stringResource(R.string.albums), albumNames.joinToString(", "))
                            }
                        }
                    }
                    if (isVideo && previewUri != null) {
                        VideoControlBar(
                            durationMillis = durationMillis.takeIf { it > 0 } ?: (asset.durationMillis ?: 0L).toInt(),
                            positionMillis = positionMillis,
                            playing = isPlaying,
                            onTogglePlayback = {
                                val player = videoView
                                if (player != null) {
                                    if (isPlaying) player.pause() else {
                                        if (durationMillis > 0 && player.currentPosition >= durationMillis) player.seekTo(0)
                                        player.start()
                                    }
                                    isPlaying = !isPlaying
                                }
                            },
                            onSeek = { value ->
                                positionMillis = value
                                if (durationMillis > 0) videoView?.seekTo(value)
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { detailsOpen = !detailsOpen }, modifier = Modifier.weight(1f).height(48.dp)) {
                            Icon(Icons.Outlined.Info, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text(stringResource(if (detailsOpen) R.string.hide_details else R.string.details))
                        }
                        OutlinedButton(
                            onClick = {
                                model.shareOriginal(asset) { uri ->
                                    val extension = asset.title.substringAfterLast('.', "").lowercase()
                                    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                                        ?: if (isVideo) "video/*" else "image/*"
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        this.type = type
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newUri(context.contentResolver, asset.title, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_photo)))
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            enabled = state.downloadInProgress != asset.id,
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text(stringResource(R.string.share))
                        }
                        Button(
                            onClick = { model.download(asset) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            enabled = state.downloadInProgress != asset.id,
                        ) {
                            Icon(Icons.Outlined.DownloadForOffline, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text(stringResource(if (state.downloadInProgress == asset.id) R.string.saving else R.string.save))
                        }
                    }
                }
            }
        }
    }

}

/** TextureView keeps the Live Photo frame composited with Compose instead of exposing a black SurfaceView hole. */
private class LivePhotoTextureView(
    context: Context,
    private val stillOrientation: Int?,
    private var stillAspectRatio: Float,
    private val onPlaybackStarted: (Int) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onPlaybackCompleted: () -> Unit,
    private val onPlaybackError: () -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var sourceUri: Uri? = null
    private var player: MediaPlayer? = null
    private var outputSurface: Surface? = null
    private var firstFrameDelivered = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var metadataVideoWidth = 0
    private var metadataVideoHeight = 0
    private var videoRotation = 0

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setSource(uri: Uri) {
        if (sourceUri == uri) return
        sourceUri = uri
        if (isAvailable) surfaceTexture?.let(::preparePlayer)
    }

    fun updateStillAspectRatio(aspectRatio: Float) {
        if (aspectRatio > 0f && aspectRatio.isFinite() && stillAspectRatio != aspectRatio) {
            stillAspectRatio = aspectRatio
            updateVideoTransform()
        }
    }

    fun release() = releasePlayer()

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        preparePlayer(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateVideoTransform()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateVideoTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!firstFrameDelivered && player?.isPlaying == true) {
            firstFrameDelivered = true
            onFirstFrame()
        }
    }

    private fun preparePlayer(surfaceTexture: SurfaceTexture) {
        if (player != null) return
        val uri = sourceUri ?: return
        val surface = Surface(surfaceTexture)
        val nextPlayer = MediaPlayer()
        outputSurface = surface
        player = nextPlayer
        firstFrameDelivered = false
        try {
            readVideoMetadata(uri)
            nextPlayer.setDataSource(context, uri)
            nextPlayer.setSurface(surface)
            nextPlayer.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) {
                    videoWidth = width
                    videoHeight = height
                    val rotation = ((videoRotation % 360) + 360) % 360
                    val rotationAppliedByPlayer = (rotation == 90 || rotation == 270) &&
                        (width == metadataVideoHeight && height == metadataVideoWidth ||
                            abs(width.toFloat() / height - stillAspectRatio) < 0.02f)
                    if (rotationAppliedByPlayer) videoRotation = 0
                    updateVideoTransform()
                }
            }
            nextPlayer.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                onPlaybackStarted(prepared.duration.coerceAtLeast(0))
                prepared.start()
            }
            nextPlayer.setOnCompletionListener { completed ->
                if (player === completed) onPlaybackCompleted()
            }
            nextPlayer.setOnErrorListener { failed, _, _ ->
                if (player === failed) onPlaybackError()
                true
            }
            nextPlayer.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
            onPlaybackError()
        }
    }

    private fun readVideoMetadata(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        var metadataRotation = 0
        try {
            retriever.setDataSource(context, uri)
            metadataVideoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            metadataVideoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            videoWidth = metadataVideoWidth
            videoHeight = metadataVideoHeight
            metadataRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            videoWidth = 0
            videoHeight = 0
            metadataVideoWidth = 0
            metadataVideoHeight = 0
        } finally {
            runCatching { retriever.release() }
        }
        videoRotation = metadataRotation.takeUnless { ((it % 360) + 360) % 360 == 0 }
            ?: when (stillOrientation) {
                3 -> 180
                6 -> 90
                8 -> 270
                else -> 0
            }
        updateVideoTransform()
    }

    /** Fit playback into the same centered aspect-ratio canvas as the still preview. */
    private fun updateVideoTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val sourceWidth = videoWidth.toFloat()
        val sourceHeight = videoHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || sourceWidth <= 0f || sourceHeight <= 0f) return

        val rotation = ((videoRotation % 360) + 360) % 360
        val swapsDimensions = rotation == 90 || rotation == 270
        val displayWidth = if (swapsDimensions) sourceHeight else sourceWidth
        val displayHeight = if (swapsDimensions) sourceWidth else sourceHeight
        val canvasAspect =
            stillAspectRatio.takeIf { it > 0f && it.isFinite() } ?: (displayWidth / displayHeight)
        val canvasWidth = min(viewWidth, viewHeight * canvasAspect)
        val canvasHeight = canvasWidth / canvasAspect
        val fitScale = min(canvasWidth / displayWidth, canvasHeight / displayHeight)
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val matrixValues = FloatArray(9)
        when (rotation) {
            90 -> {
                matrixValues[0] = 0f
                matrixValues[1] = -(fitScale * sourceHeight / viewHeight)
                matrixValues[3] = fitScale * sourceWidth / viewWidth
                matrixValues[4] = 0f
            }
            180 -> {
                matrixValues[0] = -(fitScale * sourceWidth / viewWidth)
                matrixValues[1] = 0f
                matrixValues[3] = 0f
                matrixValues[4] = -(fitScale * sourceHeight / viewHeight)
            }
            270 -> {
                matrixValues[0] = 0f
                matrixValues[1] = fitScale * sourceHeight / viewHeight
                matrixValues[3] = -(fitScale * sourceWidth / viewWidth)
                matrixValues[4] = 0f
            }
            else -> {
                matrixValues[0] = fitScale * sourceWidth / viewWidth
                matrixValues[1] = 0f
                matrixValues[3] = 0f
                matrixValues[4] = fitScale * sourceHeight / viewHeight
            }
        }
        matrixValues[2] = centerX - matrixValues[0] * centerX - matrixValues[1] * centerY
        matrixValues[5] = centerY - matrixValues[3] * centerX - matrixValues[4] * centerY
        matrixValues[6] = 0f
        matrixValues[7] = 0f
        matrixValues[8] = 1f
        setTransform(Matrix().apply { setValues(matrixValues) })
    }

    private fun releasePlayer() {
        val previousPlayer = player
        player = null
        runCatching { previousPlayer?.release() }
        val previousSurface = outputSurface
        outputSurface = null
        runCatching { previousSurface?.release() }
    }
}

@Composable
private fun VideoControlBar(
    durationMillis: Int,
    positionMillis: Int,
    playing: Boolean,
    onTogglePlayback: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Slider(
            value = positionMillis.toFloat().coerceIn(0f, durationMillis.coerceAtLeast(1).toFloat()),
            onValueChange = { onSeek(it.toInt()) },
            valueRange = 0f..durationMillis.coerceAtLeast(1).toFloat(),
            enabled = durationMillis > 0,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTogglePlayback, modifier = Modifier.semantics { contentDescription = context.getString(if (playing) R.string.pause_video else R.string.play_video) }) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, tint = Color.White)
            }
            Text(formatViewerDuration(positionMillis.toLong()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.78f))
            Spacer(Modifier.weight(1f))
            Text(formatViewerDuration(durationMillis.toLong()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.78f))
        }
    }
}

private fun formatViewerDate(epoch: Long, context: Context): String = runCatching {
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL, java.text.DateFormat.SHORT, Locale.getDefault())
        .format(java.util.Date(epoch))
}.getOrDefault(context.getString(R.string.unavailable))

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.68f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

private fun formatViewerSize(bytes: Long, context: Context): String = when {
    bytes <= 0 -> context.getString(R.string.unavailable)
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> context.getString(
        R.string.size_kib_bytes,
        String.format(Locale.getDefault(), "%.1f", bytes / 1_024.0),
        java.text.NumberFormat.getIntegerInstance().format(bytes),
    )
    else -> context.getString(
        R.string.size_mib_bytes,
        String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0),
        java.text.NumberFormat.getIntegerInstance().format(bytes),
    )
}

private fun mediaDescription(asset: PhotoAsset, context: Context): String {
    val kind = when (asset.mediaKind) {
        MediaKind.Photo -> context.getString(R.string.media_photo)
        MediaKind.Video -> context.getString(R.string.media_video)
        MediaKind.Raw -> context.getString(R.string.media_raw_photo)
        MediaKind.LivePhoto -> context.getString(R.string.media_live_photo, context.getString(R.string.live_photos_name))
    }
    val format = asset.title.substringAfterLast('.', "").takeIf(String::isNotBlank)?.uppercase(Locale.ROOT)
    return if (format == null) kind else context.getString(R.string.format_with_type, kind, format)
}

private fun localizedPhotoAlbumTitle(title: String, context: Context): String = when {
    title.equals("All Photos", ignoreCase = true) -> context.getString(R.string.all_photos)
    title.equals("Favorites", ignoreCase = true) -> context.getString(R.string.favorites)
    title.equals("Recently Deleted", ignoreCase = true) -> context.getString(R.string.recently_deleted)
    title.equals("Hidden", ignoreCase = true) -> context.getString(R.string.hidden)
    else -> title
}

private fun formatViewerOrientation(value: Int, context: Context): String {
    val name = when (value) {
        1 -> context.getString(R.string.orientation_normal)
        2 -> context.getString(R.string.orientation_mirrored_horizontal)
        3 -> context.getString(R.string.orientation_rotated_180)
        4 -> context.getString(R.string.orientation_mirrored_vertical)
        5 -> context.getString(R.string.orientation_transposed)
        6 -> context.getString(R.string.orientation_rotated_90)
        7 -> context.getString(R.string.orientation_transverse)
        8 -> context.getString(R.string.orientation_rotated_270)
        else -> context.getString(R.string.orientation_code, value)
    }
    return context.getString(R.string.orientation_with_code, name, value)
}

private fun formatViewerDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
