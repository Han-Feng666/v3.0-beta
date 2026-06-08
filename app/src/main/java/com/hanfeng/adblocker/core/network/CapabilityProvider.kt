package com.HanFeng.core.network

import android.content.Context

interface CapabilityProvider {
    val name: String

    fun isAvailable(context: Context): Boolean
}
