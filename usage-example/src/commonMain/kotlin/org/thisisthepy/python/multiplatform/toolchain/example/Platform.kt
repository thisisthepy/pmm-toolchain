package org.thisisthepy.python.multiplatform.toolchain.example


interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
