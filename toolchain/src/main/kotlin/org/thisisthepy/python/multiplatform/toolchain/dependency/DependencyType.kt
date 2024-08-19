package org.thisisthepy.python.multiplatform.toolchain.dependency


enum class DependencyType(private val type: String) {
    IMPLEMENTATION("Implementation"), API("Api");

    override fun toString(): String {
        return type
    }
}
