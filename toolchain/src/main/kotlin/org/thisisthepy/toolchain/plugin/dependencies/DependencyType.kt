package org.thisisthepy.toolchain.plugin.dependencies


enum class DependencyType(private val type: String) {
    IMPLEMENTATION("Implementation"), API("Api");

    override fun toString(): String {
        return type
    }
}
