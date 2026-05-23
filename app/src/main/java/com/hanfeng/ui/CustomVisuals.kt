package com.HanFeng.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView

private val supportedImageExtensions = listOf("png", "jpg", "jpeg", "webp")

fun ImageView.applyCustomAssetBackground(assetBaseName: String) {
    val customDrawable = loadCustomAssetDrawable(context, assetBaseName) ?: return
    setImageDrawable(customDrawable)
}

fun loadCustomAssetDrawable(context: Context, assetBaseName: String): Drawable? {
    for (extension in supportedImageExtensions) {
        val assetPath = "$assetBaseName.$extension"
        val drawable = runCatching {
            context.assets.open(assetPath).use { input ->
                Drawable.createFromStream(input, assetPath)
            }
        }.getOrNull()
        if (drawable != null) {
            return drawable
        }
    }
    return null
}
