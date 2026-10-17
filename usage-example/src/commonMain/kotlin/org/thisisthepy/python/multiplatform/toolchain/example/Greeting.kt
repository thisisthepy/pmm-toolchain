package org.thisisthepy.python.multiplatform.toolchain.example


class Greeting {
    private val platform = getPlatform()

    fun greet(): String {
        return "Hello, ${platform.name}!"
    }
}
