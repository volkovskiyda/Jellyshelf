package com.gmail.volkovskiyda.jellyshelf.domain.model

data class ScrollPosition(val index: Int, val offset: Int) {
    companion object {
        val Zero = ScrollPosition(0, 0)
    }
}

/**
 * Scroll position anchored to a stable per-item key (the video anchor, i.e. its file name) rather
 * than a raw list index, so it survives items being added, removed or renamed.
 */
data class AnchorPosition(val anchor: String, val offset: Int)
