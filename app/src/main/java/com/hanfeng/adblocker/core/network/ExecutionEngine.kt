package com.HanFeng.core.network

import android.content.Context

interface ExecutionEngine {
    val mode: NetworkMode

    fun isAvailable(context: Context): Boolean
}
