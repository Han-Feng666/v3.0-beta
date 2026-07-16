package com.HanFeng.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val supportedImageExtensions = listOf("png", "jpg", "jpeg", "webp")
private val customVisualsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private const val CUSTOM_VISUALS_JOB_TAG_KEY = -1008611

fun ImageView.applyCustomAssetBackground(assetBaseName: String) {
    (getTag(CUSTOM_VISUALS_JOB_TAG_KEY) as? Job)?.cancel()
    val appContext = context.applicationContext
    val job = customVisualsScope.launch {
        val customDrawable = withContext(Dispatchers.IO) {
            loadCustomAssetDrawable(appContext, assetBaseName)
        } ?: return@launch
        if (context.applicationContext == appContext) {
            setImageDrawable(customDrawable)
        }
    }
    setTag(CUSTOM_VISUALS_JOB_TAG_KEY, job)
}

fun ImageView.applyCustomFileBackground(filePath: String?) {
    (getTag(CUSTOM_VISUALS_JOB_TAG_KEY) as? Job)?.cancel()
    val appContext = context.applicationContext
    val job = customVisualsScope.launch {
        val customDrawable = withContext(Dispatchers.IO) {
            if (filePath != null) loadCustomFileDrawable(appContext, filePath) else null
        } ?: return@launch
        if (context.applicationContext == appContext) {
            setImageDrawable(customDrawable)
        }
    }
    setTag(CUSTOM_VISUALS_JOB_TAG_KEY, job)
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

fun loadCustomFileDrawable(context: Context, filePath: String): Drawable? {
    val file = File(filePath)
    if (!file.exists()) return null
    return runCatching {
        file.inputStream().use { input ->
            Drawable.createFromStream(input, filePath)
        }
    }.getOrNull()
}
