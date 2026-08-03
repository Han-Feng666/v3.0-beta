package moe.shizuku.manager.ktx

fun Any?.logd(t: Throwable? = null) {
    if (t == null) {
        android.util.Log.d("AdbClient", this?.toString() ?: "null")
    } else {
        android.util.Log.d("AdbClient", this?.toString() ?: "null", t)
    }
}
