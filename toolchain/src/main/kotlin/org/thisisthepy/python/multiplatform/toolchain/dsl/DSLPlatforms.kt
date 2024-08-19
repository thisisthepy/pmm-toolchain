package org.thisisthepy.python.multiplatform.toolchain.dsl


open class PlatformsExtension {
    var android: AndroidPlatformExtension? = null
    var ios: IosPlatformExtension? = null
    var desktop: DesktopPlatformExtension? = null

    fun android(name: String = "android", action: AndroidPlatformExtension.() -> Unit) {
        android = AndroidPlatformExtension(name).apply(action)
    }
    fun ios(action: IosPlatformExtension.() -> Unit) {
        ios = IosPlatformExtension().apply(action)
    }
    fun desktop(action: DesktopPlatformExtension.() -> Unit = {}) {
        desktop = DesktopPlatformExtension().apply(action)
    }

    fun androidArm64() = AndroidVariant("androidArm64").apply { arch = "arm64" }
    fun androidArm32() = AndroidVariant("androidArm32").apply { arch = "arm32" }
    fun androidX64() = AndroidVariant("androidX64").apply { arch = "x64" }
    fun androidX86() = AndroidVariant("androidX86").apply { arch = "x86" }

    fun iosArm64() = IosVariant("iosArm64").apply { arch = "arm64" }
    fun iosX64() = IosVariant("iosX64").apply { arch = "x64" }
    fun iosSimulatorArm64() = IosVariant("iosSimulatorArm64").apply { arch = "simulator-arm64" }

    fun macosX64() = DesktopVariant("macosX64").apply { os = "macosX64" }
    fun macosArm64() = DesktopVariant("macosArm64").apply { os = "macosArm64" }
    fun linuxX64() = DesktopVariant("linuxX64").apply { os = "linuxX64" }
    fun linuxArm64() = DesktopVariant("linuxArm64").apply { os = "linuxArm64" }
    fun mingwX64() = DesktopVariant("mingwX64").apply { os = "mingwX64" }
}

open class AndroidPlatformExtension(val name: String = "android") {
    var androidSdk: Int = 0
    val variants: MutableList<AndroidVariant> = mutableListOf()

    fun variants(vararg v: AndroidVariant) {
        variants.addAll(v)
    }
}

open class AndroidVariant(val name: String) {
    var arch: String = ""
    var binaries = BinariesExtension()
}

open class IosPlatformExtension {
    var iosSdk: Int = 0
    val variants: MutableList<IosVariant> = mutableListOf()

    fun variants(vararg v: IosVariant) {
        variants.addAll(v)
    }
}

open class IosVariant(val name: String) {
    var arch: String = ""
    var binaries = BinariesExtension()
}

open class DesktopPlatformExtension {
    val variants: MutableList<DesktopVariant> = mutableListOf()

    fun variants(vararg v: DesktopVariant) {
        variants.addAll(v)
    }
}

open class DesktopVariant(val name: String) {
    var os: String = ""
    var binaries = BinariesExtension()
}

open class BinariesExtension {
    var frozenPackConfig: FrozenPackConfig? = null

    fun frozenPack(action: FrozenPackConfig.() -> Unit) {
        frozenPackConfig = FrozenPackConfig().apply(action)
    }
}

sealed class BuildTypeEnum {
    enum class DEBUG {
        INSTANT, BYTECODE
    }
    enum class RELEASE {
        BYTECODE, NATIVE, MIXED
    }
}

open class FrozenPackConfig {
    var test = BuildTypeEnum.DEBUG.INSTANT
    fun a() {
        test = BuildTypeEnum.DEBUG.BYTECODE
        // test = BuildTypeEnum.RELEASE.BYTECODE
    }
}