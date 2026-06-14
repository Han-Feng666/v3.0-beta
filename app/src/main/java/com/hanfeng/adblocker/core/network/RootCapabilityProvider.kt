package com.HanFeng.core.network

import android.content.Context

interface RootCapabilityProvider : CapabilityProvider {
    fun detect(context: Context): RootCapabilityState
}

data class RootCapabilityState(
    val available: Boolean,
    val manager: String? = null,
    val details: String? = null
)

object RootShellCapabilityProvider : RootCapabilityProvider {
    override val name: String = "root"

    override fun isAvailable(context: Context): Boolean = false

    override fun detect(context: Context): RootCapabilityState {
        return RootCapabilityState(
            available = false,
            manager = null,
            details = "Root features disabled"
        )
    }
}

object NoOpRootCapabilityProvider : RootCapabilityProvider by RootShellCapabilityProvider
