package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.SocNames

object DeviceSupport {
    fun adreno(): Boolean = GpuInfo.adrenoPresent()

    fun mali(): Boolean = !adreno() && GpuInfo.maliPresent()

    fun supported(): Boolean = adreno() || mali()

    fun gpuName(context: Context): String {
        SocNames.kirin(Build.HARDWARE.orEmpty())?.let { return it }
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
