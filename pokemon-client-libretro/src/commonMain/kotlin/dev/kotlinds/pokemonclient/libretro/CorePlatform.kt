package dev.kotlinds.pokemonclient.libretro

/** Platform naming used by the libretro buildbot. */
internal enum class CorePlatform(val buildbotPath: String, val extension: String) {
    MAC_ARM64("apple/osx/arm64", "dylib"),
    MAC_X64("apple/osx/x86_64", "dylib"),
    LINUX_X64("linux/x86_64", "so"),
    WINDOWS_X64("windows/x86_64", "dll");

    companion object {
        /** The buildbot platform for an OS name and whether the CPU is ARM 64-bit. */
        fun of(osName: String, arm64: Boolean): CorePlatform {
            val os = osName.lowercase()
            return when {
                "mac" in os || "darwin" in os -> if (arm64) MAC_ARM64 else MAC_X64
                "win" in os -> WINDOWS_X64
                else -> LINUX_X64
            }
        }
    }
}
