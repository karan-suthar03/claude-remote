@file:OptIn(ExperimentalCoroutinesApi::class)

package me.karansuthar.clauderemote.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import me.karansuthar.clauderemote.data.ChatImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Decoding and caching (for display)
// ---------------------------------------------------------------------------------------------

/** At most two decodes at once, so a fling through many images doesn't starve the UI. */
private val decodeDispatcher = Dispatchers.Default.limitedParallelism(2)

/** Decoded bitmaps keyed by content key and size, capped at ~32 MB. */
private val bitmapCache = object : LruCache<String, ImageBitmap>(32 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = max(1, value.width * value.height * 4 / 1024)
}

/** Width / height by content key: keeps a re-composed image the same size before it decodes again. */
private val aspectCache = LruCache<String, Float>(1024)

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    class Ready(val bitmap: ImageBitmap) : ImageLoad
    data object Failed : ImageLoad
}

/** Base64 [data] decoded and downsampled so its long side is at most [maxPx]. Blocking. */
private fun decodeBase64Image(data: String, maxPx: Int): Bitmap {
    val bytes = Base64.decode(data, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) throw IOException("Not an image")
    var sample = 1
    while (max(w, h) / (sample * 2) >= maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IOException("Couldn't decode")
    return scaleToFit(bitmap, maxPx)
}

private fun scaleToFit(b: Bitmap, maxPx: Int): Bitmap {
    val long = max(b.width, b.height)
    if (long <= maxPx) return b
    val scale = maxPx.toFloat() / long
    return Bitmap.createScaledBitmap(
        b,
        max(1, (b.width * scale).roundToInt()),
        max(1, (b.height * scale).roundToInt()),
        true,
    )
}

/**
 * [image] decoded for display at up to [maxPx] on its long side: off the main thread, only while
 * composed (scrolling past cancels a queued decode), from the cache when it is there.
 */
/** Fetches the data of an image sent by reference (snapshot history); provided by the chat screen. */
val LocalImageFetcher = androidx.compose.runtime.staticCompositionLocalOf<(suspend (String) -> String)?> { null }

@Composable
private fun rememberImageLoad(image: ChatImage, maxPx: Int, cache: Boolean = true): ImageLoad {
    val fetch = LocalImageFetcher.current
    val contentKey = remember(image) { image.cacheKey }
    val key = "$contentKey@$maxPx"
    val state = remember(key) {
        mutableStateOf<ImageLoad>(bitmapCache.get(key)?.let { ImageLoad.Ready(it) } ?: ImageLoad.Loading)
    }
    LaunchedEffect(key) {
        if (state.value !is ImageLoad.Loading) return@LaunchedEffect
        state.value = try {
            val bitmap = withContext(decodeDispatcher) {
                // History images arrive as references: download only when one is actually shown.
                val data = if (image.data.isEmpty() && image.ref != null) (fetch ?: error("no fetcher"))(image.ref) else image.data
                val b = decodeBase64Image(data, maxPx).asImageBitmap()
                if (cache) bitmapCache.put(key, b)
                aspectCache.put(contentKey, b.width.toFloat() / b.height)
                b
            }
            ImageLoad.Ready(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageLoad.Failed
        } catch (e: OutOfMemoryError) {
            ImageLoad.Failed
        }
    }
    return state.value
}

/** One image filling [modifier]'s box: a spinner while it decodes, a broken-image icon if it can't. */
@Composable
private fun ChatImageBox(
    load: ImageLoad,
    contentScale: ContentScale,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = "View image", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (load) {
            is ImageLoad.Ready -> Image(
                load.bitmap,
                contentDescription = "Image",
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
            ImageLoad.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            ImageLoad.Failed -> Icon(
                Icons.Default.BrokenImage,
                contentDescription = "Couldn't show the image",
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Message and step images
// ---------------------------------------------------------------------------------------------

private val USER_IMAGES_WIDTH = 312.dp
private val SINGLE_MAX_HEIGHT = 240.dp
private val GRID_GAP = 4.dp
private val GRID_CELL = (USER_IMAGES_WIDTH - GRID_GAP) / 2
private val STEP_THUMB = 96.dp

/**
 * A user message's images: one fills the bubble's width (at most [SINGLE_MAX_HEIGHT] tall); more
 * are a two-column grid of square thumbnails. Tapping one opens the full-screen viewer.
 */
@Composable
fun MessageImages(images: List<ChatImage>, modifier: Modifier = Modifier) {
    if (images.isEmpty()) return
    var viewing by remember { mutableStateOf<ChatImage?>(null) }
    val density = LocalDensity.current
    val shape = RoundedCornerShape(14.dp)
    if (images.size == 1) {
        val image = images[0]
        val maxPx = with(density) { USER_IMAGES_WIDTH.roundToPx() }
        val load = rememberImageLoad(image, maxPx)
        val contentKey = remember(image) { image.cacheKey }
        val aspect = ((load as? ImageLoad.Ready)?.bitmap?.let { it.width.toFloat() / it.height }
            ?: aspectCache.get(contentKey) ?: (4f / 3f)).coerceIn(0.1f, 10f)
        var width = min(USER_IMAGES_WIDTH.value, SINGLE_MAX_HEIGHT.value * aspect)
        val height = (width / aspect).coerceAtLeast(48f)
        if (load !is ImageLoad.Ready) width = USER_IMAGES_WIDTH.value.coerceAtMost(max(width, 160f))
        ChatImageBox(
            load,
            ContentScale.Fit,
            modifier.size(Dp(width), Dp(height)).clip(shape),
        ) { viewing = image }
    } else {
        val maxPx = with(density) { GRID_CELL.roundToPx() }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
            for (row in images.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    for (image in row) {
                        ChatImageBox(
                            rememberImageLoad(image, maxPx),
                            ContentScale.Crop,
                            Modifier.size(GRID_CELL).clip(shape),
                        ) { viewing = image }
                    }
                }
            }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

/** A tool result's images as a row of thumbnails; tapping one opens the viewer. */
@Composable
fun StepImages(images: List<ChatImage>, modifier: Modifier = Modifier) {
    if (images.isEmpty()) return
    var viewing by remember { mutableStateOf<ChatImage?>(null) }
    val maxPx = with(LocalDensity.current) { STEP_THUMB.roundToPx() }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (image in images) {
            ChatImageBox(rememberImageLoad(image, maxPx), ContentScale.Crop, Modifier.size(STEP_THUMB).clip(shape)) { viewing = image }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

/** Full screen on black: fitted, pinch to zoom and pan, double-tap to reset, tap or back to close. */
@Composable
fun ImageViewer(image: ChatImage, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        // Not cached: a screen-sized bitmap would push many thumbnails out of the cache.
        val load = rememberImageLoad(image, VIEWER_MAX_PX, cache = false)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                    )
                }
                .pointerInput(Unit) {
                    // size: this box's size, from the PointerInputScope.
                    detectTransformGestures { _, pan, zoom, _ ->
                        val s = (scale * zoom).coerceIn(1f, 6f)
                        val maxX = (s - 1f) * size.width / 2f
                        val maxY = (s - 1f) * size.height / 2f
                        scale = s
                        offset = Offset(
                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                            (offset.y + pan.y).coerceIn(-maxY, maxY),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (load) {
                is ImageLoad.Ready -> Image(
                    load.bitmap,
                    contentDescription = "Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
                ImageLoad.Loading -> CircularProgressIndicator(color = Color.White)
                ImageLoad.Failed -> Icon(
                    Icons.Default.BrokenImage,
                    contentDescription = "Couldn't show the image",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private const val VIEWER_MAX_PX = 2048

// ---------------------------------------------------------------------------------------------
// Preparing picked images for sending
// ---------------------------------------------------------------------------------------------

/** Long side of a sent image. */
private const val SEND_MAX_SIDE = 1600
private const val JPEG_QUALITY = 85
/** A transparent image stays PNG only up to this size; bigger ones become JPEG on white. */
private const val PNG_MAX_BYTES = 1_000_000
private const val PENDING_THUMB_PX = 192

/** A picked image ready to send, and a small thumbnail of it for the input bar. */
class PreparedImage(val image: ChatImage, val thumb: ImageBitmap)

/**
 * Reads [uri], orients it, shrinks it to [SEND_MAX_SIDE] and re-encodes it (JPEG quality 85, or
 * PNG when it has transparency and stays small). Runs on Dispatchers.IO; throws on failure.
 */
suspend fun prepareImage(context: Context, uri: Uri): PreparedImage = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bitmap = if (Build.VERSION.SDK_INT >= 28) decodeForSend28(resolver, uri) else decodeForSendLegacy(resolver, uri)
    val png = if (hasTransparency(bitmap)) {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (out.size() <= PNG_MAX_BYTES) out.toByteArray() else null
    } else null
    val image = if (png != null) {
        ChatImage("image/png", Base64.encodeToString(png, Base64.NO_WRAP))
    } else {
        val opaque = if (bitmap.hasAlpha()) onWhite(bitmap) else bitmap
        val out = ByteArrayOutputStream()
        if (!opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("Couldn't encode")
        ChatImage("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }
    PreparedImage(image, scaleToFit(bitmap, PENDING_THUMB_PX).asImageBitmap())
}

/** API 28+: ImageDecoder applies the EXIF orientation itself. */
@RequiresApi(28)
private fun decodeForSend28(resolver: ContentResolver, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(resolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val long = max(w, h)
        if (long > SEND_MAX_SIDE) {
            val scale = SEND_MAX_SIDE.toFloat() / long
            decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
        }
    }
}

/** API 26-27: BitmapFactory with inSampleSize, then the EXIF orientation applied by hand. */
private fun decodeForSendLegacy(resolver: ContentResolver, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: throw IOException("Couldn't open the image")
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) throw IOException("Not an image")
    var sample = 1
    while (max(w, h) / (sample * 2) >= SEND_MAX_SIDE) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: throw IOException("Couldn't decode the image")
    val orientation = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (e: Exception) {
        null
    } ?: ExifInterface.ORIENTATION_NORMAL
    return oriented(scaleToFit(decoded, SEND_MAX_SIDE), orientation)
}

private fun oriented(b: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.postRotate(90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.postRotate(270f)
            m.postScale(-1f, 1f)
        }
        else -> return b
    }
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
}

/** Whether any pixel is not fully opaque. */
private fun hasTransparency(b: Bitmap): Boolean {
    if (!b.hasAlpha()) return false
    val w = b.width
    val row = IntArray(w)
    for (y in 0 until b.height) {
        b.getPixels(row, 0, w, 0, y, w, 1)
        for (p in row) if (p ushr 24 != 0xFF) return true
    }
    return false
}

/** [b] drawn over white, for JPEG (which would turn transparent pixels black). */
private fun onWhite(b: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.drawBitmap(b, 0f, 0f, null)
    return out
}
