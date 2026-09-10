package com.gmail.volkovskiyda.jellyshelf.ui.player

import androidx.annotation.StringRes
import androidx.compose.ui.layout.ContentScale
import com.gmail.volkovskiyda.jellyshelf.R
import com.gmail.volkovskiyda.jellyshelf.domain.model.VideoScaleMode

/**
 * How each mode maps onto Compose's own scaling, and onto the name the button and the pill show.
 *
 * Both `when`s are exhaustive with no `else`, so a fourth mode fails the build here rather than
 * silently rendering (or naming) itself as one of these three.
 */
internal val VideoScaleMode.contentScale: ContentScale
    get() = when (this) {
        VideoScaleMode.FIT -> ContentScale.Fit
        VideoScaleMode.ZOOM -> ContentScale.Crop
        VideoScaleMode.STRETCH -> ContentScale.FillBounds
    }

@StringRes
internal fun VideoScaleMode.labelRes(): Int = when (this) {
    VideoScaleMode.FIT -> R.string.scale_mode_fit
    VideoScaleMode.ZOOM -> R.string.scale_mode_zoom
    VideoScaleMode.STRETCH -> R.string.scale_mode_stretch
}
