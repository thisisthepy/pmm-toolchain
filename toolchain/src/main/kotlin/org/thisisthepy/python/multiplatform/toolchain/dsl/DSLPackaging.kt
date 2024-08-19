package org.thisisthepy.python.multiplatform.toolchain.dsl


open class PackagingExtension {
    var embedLevel: Int = 0
    var fileName: String = "app"
    val hotReload: HotReloadExtension = HotReloadExtension()
    val codePush: CodePushExtension = CodePushExtension()

    fun hotReload(action: HotReloadExtension.() -> Unit) = hotReload.apply(action)
    fun codePush(action: CodePushExtension.() -> Unit) = codePush.apply(action)
}

open class HotReloadExtension {
    var redirectErrorStream: Boolean = false
    var serverHost: String = ""
    val cert: CertExtension = CertExtension()

    fun cert(action: CertExtension.() -> Unit) = cert.apply(action)
}

open class CodePushExtension {
    var serverHost: String = ""
    val cert: CertExtension = CertExtension()
    val uploadConfig: UploadConfigExtension = UploadConfigExtension()

    fun cert(action: CertExtension.() -> Unit) = cert.apply(action)
    fun uploadConfig(action: UploadConfigExtension.() -> Unit) = uploadConfig.apply(action)
}

open class CertExtension {
    var keyStore: String? = null
    var autoGenerate: Boolean = false
}

open class UploadConfigExtension {
    var forceUpload: Boolean = false
    val login: LoginExtension = LoginExtension()

    fun login(action: LoginExtension.() -> Unit) = login.apply(action)
}

open class LoginExtension {
    var id: String = ""
    var password: String = ""
}
