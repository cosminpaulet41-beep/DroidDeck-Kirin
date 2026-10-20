package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import java.io.File

data class GpuInfo(
    val name: String,
    val model: Int,
    val family: Family,
    val soc: String,
    val oneUi8Gen2: Boolean,
    val kgslName: String = "",
    val modelSource: String = "",
    val vendor: Vendor = if (family == Family.MALI) Vendor.MALI else if (family == Family.NOT_ADRENO) Vendor.UNKNOWN else Vendor.ADRENO,
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        ADRENO_UNKNOWN("Adreno"),
        MALI("Mali"),
        NOT_ADRENO("Not an Adreno or Mali GPU");

        fun label(context: Context): String = if (this == NOT_ADRENO) context.getString(R.string.gpuinfo_not_adreno) else label
    }

    // Turnip only opens an Adreno through KGSL; a Mali is driven through Arm's mali_kbase and needs
    // the vendor ICD (Android side) or PanVK (Linux side) instead.
    enum class Vendor {
        ADRENO, MALI, UNKNOWN;

        val adreno: Boolean get() = this == ADRENO
    }

    enum class Support { TESTED, UNTESTED, UNSUPPORTED }

    val support: Support
        get() = when {
            family == Family.NOT_ADRENO -> Support.UNSUPPORTED
            family == Family.MALI -> Support.UNTESTED
            family == Family.A6XX && model == 650 -> Support.TESTED
            family == Family.A8XX -> Support.TESTED
            family == Family.A7XX && model >= 725 -> Support.TESTED
            else -> Support.UNTESTED
        }

    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> when (family) {
                Family.A7XX_LOW -> "Experimental: its drivers are test builds"
                Family.MALI -> "Experimental: needs a PanVK or vendor Vulkan driver, not Turnip"
                else -> "Outside tested hardware (Adreno 650, 725 and newer): it may not run"
            }
            Support.UNSUPPORTED -> "Not supported: DroidDeck needs an Adreno (Snapdragon) or Mali GPU"
        }

    fun supportText(context: Context): String = context.getString(when (support) {
        Support.TESTED -> R.string.gpuinfo_supported
        Support.UNTESTED -> when (family) {
            Family.A7XX_LOW -> R.string.gpuinfo_experimental
            Family.MALI -> R.string.gpuinfo_experimental_mali
            else -> R.string.gpuinfo_below_tested
        }
        Support.UNSUPPORTED -> R.string.gpuinfo_unsupported
    })

    fun displayName(context: Context): String = if (name == UNNAMED) context.getString(R.string.gpu_this_gpu) else name

    companion object {
        private const val UNNAMED = "this GPU"

        @JvmStatic
        fun adrenoPresent(): Boolean =
            File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()

        @JvmStatic
        fun maliNode(): String? = File("/sys/class/misc").listFiles()
            ?.map { it.name }
            ?.firstOrNull { it.startsWith("mali") && it.length > 4 && it.substring(4).all(Char::isDigit) }
            ?.let { "/dev/$it" }
            ?: "/dev/mali0".takeIf { File(it).exists() }

        // A Mali is recognised by what only Arm's stack has: the kbase node, a Mali devfreq node,
        // or the vendor's Mali EGL/Vulkan build named by the platform.
        @JvmStatic
        fun maliPresent(): Boolean = maliNode() != null ||
            File("/sys/class/devfreq").listFiles()?.any { it.name.contains("mali", ignoreCase = true) } == true ||
            File("/sys/class/misc/mali0").exists() ||
            systemProperty("ro.hardware.egl").contains("mali", ignoreCase = true) ||
            systemProperty("ro.hardware.vulkan").contains("mali", ignoreCase = true)

        @JvmStatic
        fun detectVendor(): Vendor = when {
            adrenoPresent() -> Vendor.ADRENO
            maliPresent() -> Vendor.MALI
            else -> Vendor.UNKNOWN
        }

        fun detect(): GpuInfo {
            val vendor = detectVendor()
            val adreno = vendor == Vendor.ADRENO
            val raw = listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/gpu_chipid")
                .firstNotNullOfOrNull { FileUtils.readString(File(it))?.trim()?.takeIf(String::isNotEmpty) }
            val soc = when (vendor) {
                Vendor.ADRENO -> SocNames.label()
                Vendor.MALI -> SocNames.label().ifEmpty { SocNames.kirin(Build.HARDWARE).orEmpty() }
                Vendor.UNKNOWN -> ""
            }
            val fromKernel = raw?.let { threeDigits(it) } ?: 0
            val fromVulkan = if (fromKernel > 0 || !adreno) 0 else VulkanInfo.cachedOrNull()?.get("device")?.let { threeDigits(it) } ?: 0
            val fromPlatform = if (fromKernel > 0 || fromVulkan > 0 || !adreno) 0 else platformModel()
            val model = maxOf(fromKernel, fromVulkan, fromPlatform)
            val source = when {
                fromKernel > 0 -> "kernel"
                fromVulkan > 0 -> "vulkan"
                fromPlatform > 0 -> "platform"
                vendor == Vendor.MALI -> "vulkan"
                else -> ""
            }
            val family = familyOf(vendor, model)
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            val name = when (vendor) {
                Vendor.ADRENO -> if (model > 0) "Adreno $model" else "Adreno"
                Vendor.MALI -> maliName(VulkanInfo.cachedOrNull()?.get("device"))
                Vendor.UNKNOWN -> Build.HARDWARE.ifBlank { UNNAMED }
            }
            return GpuInfo(
                name = name,
                model = model, family = family, soc = soc,
                oneUi8Gen2 = samsung && model == 740,
                kgslName = raw.orEmpty(), modelSource = source, vendor = vendor,
            )
        }

        internal fun maliName(vulkanDevice: String?): String =
            vulkanDevice?.trim()?.takeIf { it.contains("mali", ignoreCase = true) } ?: "Mali"

        internal fun threeDigits(text: String): Int = Regex("""(\d{3})""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        private val PLATFORMS = mapOf(
            "msmnile" to 640, "kona" to 650, "lahaina" to 660, "taro" to 730, "cape" to 730,
            "kalama" to 740, "pineapple" to 750, "sun" to 830,
        )

        internal fun platformModel(platform: String = systemProperty("ro.board.platform")): Int =
            PLATFORMS[platform.lowercase()] ?: PLATFORMS[systemProperty("ro.vendor.qti.soc_name").lowercase()] ?: 0

        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }

        internal fun familyOf(adreno: Boolean, model: Int): Family = when {
            !adreno -> Family.NOT_ADRENO
            model == 0 -> Family.ADRENO_UNKNOWN
            model >= 800 -> Family.A8XX
            model in listOf(710, 720, 722) -> Family.A7XX_LOW
            model >= 700 -> Family.A7XX
            model >= 600 -> Family.A6XX
            else -> Family.ADRENO_UNKNOWN
        }

        internal fun familyOf(vendor: Vendor, model: Int): Family = when (vendor) {
            Vendor.MALI -> Family.MALI
            else -> familyOf(vendor == Vendor.ADRENO, model)
        }
    }
}
