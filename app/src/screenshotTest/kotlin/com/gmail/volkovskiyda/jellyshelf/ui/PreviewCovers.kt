package com.gmail.volkovskiyda.jellyshelf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.request.NullRequestData
import coil3.request.SuccessResult
import com.gmail.volkovskiyda.jellyshelf.domain.model.Video
import java.util.Random

/**
 * Stand-in covers for the goldens: a small landscape scene — sky, sun, two ridges — drawn on the
 * spot rather than fetched, so a reference image still never depends on the network.
 *
 * A model is just `preview-cover:<key>`; the key picks one of [PALETTES] and seeds where the sun
 * and the ridges sit, so every video in a list gets its own picture and the same video gets the
 * same picture in every golden. Scenes rather than flat colour blocks so a golden shows what the
 * crop and the rounded corners do to a real image.
 */
internal fun previewCover(key: String): String = "$PREVIEW_COVER_SCHEME$key"

/** The per-screen thumbnail resolver the list previews pass: one cover per video id. */
internal val previewThumbnail: (Video) -> String? = { previewCover(it.youtubeId) }

/**
 * What [PreviewTheme] installs as Coil's preview handler. Screenshot tests render in inspection
 * mode, where `AsyncImage` asks this instead of its image loader. A null model stays empty — the
 * same nothing a video without a cover shows in the app.
 */
@OptIn(ExperimentalCoilApi::class)
internal val PreviewCoverHandler = AsyncImagePreviewHandler { _, request ->
    val data = request.data
    if (data == NullRequestData) {
        AsyncImagePainter.State.Empty
    } else {
        val image = drawCover(data.toString().removePrefix(PREVIEW_COVER_SCHEME)).asImage()
        AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
    }
}

private const val PREVIEW_COVER_SCHEME = "preview-cover:"

/** The 64-bit golden ratio, the usual multiplier for scattering consecutive integers. */
private const val SEED_SPREAD = -0x61c8864680b583ebL

// 16:9, and big enough that the tablet detail screen's ~480 dp cover is not visibly upscaled.
private const val COVER_WIDTH = 960
private const val COVER_HEIGHT = 540

private class Palette(
    val skyTop: Int,
    val skyBottom: Int,
    val sun: Int,
    val far: Int,
    val near: Int,
)

private val PALETTES = listOf(
    Palette(0xFF2B1B4D.toInt(), 0xFFF07B4F.toInt(), 0xFFFFD27A.toInt(), 0xFF6B3A6E.toInt(), 0xFF3A2244.toInt()),
    Palette(0xFF3C8DD9.toInt(), 0xFFBFE3F5.toInt(), 0xFFFFF4C2.toInt(), 0xFF5C8FA8.toInt(), 0xFF2F5D73.toInt()),
    Palette(0xFF8FC9B9.toInt(), 0xFFE9F2D8.toInt(), 0xFFFFFFFF.toInt(), 0xFF4E8C5A.toInt(), 0xFF2D5A3A.toInt()),
    Palette(0xFF0B1430.toInt(), 0xFF2E3F7A.toInt(), 0xFFF2F0E1.toInt(), 0xFF1E2A50.toInt(), 0xFF0E1530.toInt()),
    Palette(0xFFF4A261.toInt(), 0xFFFCE1B6.toInt(), 0xFFFFFFFF.toInt(), 0xFFC97B4A.toInt(), 0xFF8C4F2E.toInt()),
    Palette(0xFFF7B2C4.toInt(), 0xFFFDE2D6.toInt(), 0xFFFFF6E0.toInt(), 0xFFB07AA1.toInt(), 0xFF6E4A7E.toInt()),
    Palette(0xFF5DA9E9.toInt(), 0xFFDDF0FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF7C93A8.toInt(), 0xFF3E5568.toInt()),
    Palette(0xFFE8833A.toInt(), 0xFFF6D08B.toInt(), 0xFFFFF1C9.toInt(), 0xFFA0522D.toInt(), 0xFF5E2F1A.toInt()),
)

/**
 * One scene per [key]. `String.hashCode` is specified by the language, not the JVM, and
 * `java.util.Random`'s sequence is specified for a given seed, so the picture is the same on every
 * machine that renders it.
 */
@Suppress("MagicNumber") // proportions of a drawing, each meaningful only where it is used
private fun drawCover(key: String): Bitmap {
    val seed = key.hashCode()
    val palette = PALETTES[Math.floorMod(seed, PALETTES.size)]
    // Ids that differ by one character hash to neighbouring seeds, and Random's first draws for
    // neighbouring seeds are nearly equal: spread the seed first, or such videos share one scene.
    val random = Random(seed.toLong() * SEED_SPREAD)
    val w = COVER_WIDTH.toFloat()
    val h = COVER_HEIGHT.toFloat()

    val bitmap = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.shader = LinearGradient(0f, 0f, 0f, h * 0.75f, palette.skyTop, palette.skyBottom, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w, h, paint)
    paint.shader = null

    paint.color = palette.sun
    val sunX = w * (0.2f + 0.6f * random.nextFloat())
    val sunY = h * (0.28f + 0.15f * random.nextFloat())
    canvas.drawCircle(sunX, sunY, h * 0.11f, paint)

    paint.color = palette.far
    canvas.drawPath(Ridge(baseline = 0.62f, amplitude = 0.22f, peaks = 4).path(w, h, random), paint)
    paint.color = palette.near
    canvas.drawPath(Ridge(baseline = 0.80f, amplitude = 0.14f, peaks = 3).path(w, h, random), paint)
    return bitmap
}

/**
 * A jagged ridge across the full width, closed along the bottom edge. [baseline] and [amplitude]
 * are fractions of the height: the valleys sit on the baseline, the peaks up to amplitude above it.
 */
private class Ridge(val baseline: Float, val amplitude: Float, val peaks: Int)

@Suppress("MagicNumber")
private fun Ridge.path(w: Float, h: Float, random: Random): Path {
    val path = Path()
    path.moveTo(0f, h * (baseline - amplitude * 0.3f * random.nextFloat()))
    val step = w / (peaks * 2)
    for (i in 1..peaks * 2) {
        val up = i % 2 == 1
        val y = if (up) baseline - amplitude * (0.5f + 0.5f * random.nextFloat()) else baseline
        path.lineTo(step * i, h * y)
    }
    path.lineTo(w, h)
    path.lineTo(0f, h)
    path.close()
    return path
}
