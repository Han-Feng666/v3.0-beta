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
import java.lang.ref.WeakReference

private val supportedImageExtensions = listOf("png", "jpg", "jpeg", "webp")
private val customVisualsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private const val CUSTOM_VISUALS_JOB_TAG_KEY = -1008611
private const val CUSTOM_VISUALS_CONTEXT_TAG_KEY = -1008612

fun ImageView.applyCustomAssetBackground(assetBaseName: String) {
    cancelCustomVisualsJob(this)
    val appContext = context.applicationContext
    setTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY, WeakReference(appContext))
    val job = customVisualsScope.launch {
        val customDrawable = withContext(Dispatchers.IO) {
            loadCustomAssetDrawable(appContext, assetBaseName)
        } ?: return@launch
        val ctxRef = getTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY) as? WeakReference<*>
        if (ctxRef?.get() == appContext) {
            setImageDrawable(customDrawable)
        }
    }
    setTag(CUSTOM_VISUALS_JOB_TAG_KEY, job)
}

fun ImageView.applyCustomFileBackground(filePath: String?) {
    cancelCustomVisualsJob(this)
    val appContext = context.applicationContext
    setTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY, WeakReference(appContext))
    val job = customVisualsScope.launch {
        val customDrawable = withContext(Dispatchers.IO) {
            if (filePath != null) loadCustomFileDrawable(appContext, filePath) else null
        } ?: return@launch
        val ctxRef = getTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY) as? WeakReference<*>
        if (ctxRef?.get() == appContext) {
            setImageDrawable(customDrawable)
        }
    }
    setTag(CUSTOM_VISUALS_JOB_TAG_KEY, job)
}

private fun cancelCustomVisualsJob(view: ImageView) {
    (view.getTag(CUSTOM_VISUALS_JOB_TAG_KEY) as? Job)?.cancel()
    view.setTag(CUSTOM_VISUALS_JOB_TAG_KEY, null)
    view.setTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY, null)
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
