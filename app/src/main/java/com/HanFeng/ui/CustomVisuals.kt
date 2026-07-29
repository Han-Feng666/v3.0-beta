package com.HanFeng.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
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

/**
 * Bitmap 参考：记录"被 cache 持有但还可能被 ImageView 引用"的 Bitmap 集合，
 * 提供 closeDrawableFor(view) 主动分离路径，让 ImageView release 时主动 dirty cache 条目，
 * 在 eviction 时检测这个集合避免 recycle 仍在显示中的 Bitmap。
 */
private val liveDisplayedBitmaps = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<android.graphics.Bitmap, Boolean>())

// 内存缓存:解码后的 Drawable 复用,避免每次 onResume 都重新 IO+解码
// maxSize 6MB 即够覆盖 3-6 张普通背景。sizeOf 用 byte 估值，并限制单条以避免一张超高分辨率图把大小炸成 60MB，
// 导致整个 cache 仅剩 1 条记录就被驱逐。
// entryRemoved 仅在 eviction 且 Bitmap 不在 liveDisplayedBitmaps 中时 recycle，
// 避免 recycle 后被仍引用的 ImageView 绘制抛 "Canvas: trying to use a recycled bitmap"。
private val customBackgroundDrawableCache = object : LruCache<String, Drawable>(6 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Drawable): Int {
        return runCatching {
            val w = value.intrinsicWidth.coerceAtLeast(1)
            val h = value.intrinsicHeight.coerceAtLeast(1)
            val byteSize = w.toLong() * h * 4L
            // 单条上限 2MB；下限 64KB（保证空 Drawable 也算占用）
            byteSize.coerceIn(64L * 1024L, 2L * 1024L * 1024L).toInt()
        }.getOrDefault(256 * 1024)
    }

    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Drawable, newValue: Drawable?) {
        // 仅回收因 eviction 而被丢弃的旧值；newValue 非空表示是 put 覆盖，需更小心判断
        if (newValue != null && newValue === oldValue) return
        runCatching {
            val bmp = (oldValue as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@runCatching
            if (!bmp.isRecycled && !bmp.isMutable && bmp !in liveDisplayedBitmaps) {
                bmp.recycle()
            }
        }
    }
}

fun ImageView.applyCustomAssetBackground(assetBaseName: String) {
    cancelCustomVisualsJob(this)
    if (!isAttachedToWindow) return
    val appContext = context.applicationContext
    setTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY, WeakReference(appContext))
    // 同步命中缓存直接设置,跳过协程开销
    customBackgroundDrawableCache.get("asset:$assetBaseName")?.let { cached ->
        markBitmapLive(cached)
        setImageDrawable(cached)
        return
    }
    val job = customVisualsScope.launch {
        val customDrawable = withContext(Dispatchers.IO) {
            loadCustomAssetDrawable(appContext, assetBaseName)
        } ?: return@launch
        customBackgroundDrawableCache.put("asset:$assetBaseName", customDrawable)
        val ctxRef = getTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY) as? WeakReference<*>
        if (ctxRef?.get() == appContext && isAttachedToWindow) {
            markBitmapLive(customDrawable)
            setImageDrawable(customDrawable)
        }
    }
    setTag(CUSTOM_VISUALS_JOB_TAG_KEY, job)
}

fun ImageView.applyCustomFileBackground(filePath: String?) {
    cancelCustomVisualsJob(this)
    if (!isAttachedToWindow) return
    val appContext = context.applicationContext
    setTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY, WeakReference(appContext))
    if (filePath == null) return
    // 同步命中缓存直接设置,跳过协程开销
    val cacheKey = "file:$filePath:${File(filePath).lastModified()}"
    customBackgroundDrawableCache.get(cacheKey)?.let { cached ->
        markBitmapLive(cached)
        setImageDrawable(cached)
        return
    }
    val job = customVisualsScope.launch {
        val customDrawable = withContext(Dispatchers.IO) {
            loadCustomFileDrawable(appContext, filePath)
        } ?: return@launch
        customBackgroundDrawableCache.put(cacheKey, customDrawable)
        val ctxRef = getTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY) as? WeakReference<*>
        if (ctxRef?.get() == appContext && isAttachedToWindow) {
            markBitmapLive(customDrawable)
            setImageDrawable(customDrawable)
        }
    }
    setTag(CUSTOM_VISUALS_JOB_TAG_KEY, job)
}

/**
 * 把 cache 里的 Bitmap 标记为"有 ImageView 显示中"，避免 LruCache eviction 时 recycle 导致绘制崩溃。
 * Drawable 的 Bitmap 引用从 setImageDrawable 时算开始，到 cancelCustomVisualsJob 时算结束。
 */
private fun markBitmapLive(drawable: Drawable?) {
    (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
        liveDisplayedBitmaps.add(it)
    }
}

private fun unmarkBitmapLive(drawable: Drawable?) {
    (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
        liveDisplayedBitmaps.remove(it)
    }
}

private fun cancelCustomVisualsJob(view: ImageView) {
    (view.getTag(CUSTOM_VISUALS_JOB_TAG_KEY) as? Job)?.cancel()
    view.setTag(CUSTOM_VISUALS_JOB_TAG_KEY, null)
    view.setTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY, null)
    // ImageView 当前显示的 Bitmap 不再被本 view 引用，从 live 集合移除让 LRU 可以回收
    unmarkBitmapLive(view.drawable)
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
