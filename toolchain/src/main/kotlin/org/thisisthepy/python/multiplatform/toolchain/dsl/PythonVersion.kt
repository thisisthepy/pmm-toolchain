package org.thisisthepy.python.multiplatform.toolchain.dsl

/**
 * The release channel a CPython version string belongs to, matching how CPython itself tags a
 * release: `a` for alpha, `rc` for release candidate, and no suffix for a final/normal release.
 *
 * Ordered `ALPHA, RC, NORMAL` to match Issue #2's checklist wording ("Version Enum (alpha, rc,
 * normal)") verbatim.
 *
 * This is classification only, not resolution: "do not support automatic build for new python
 * release" (the same checklist item's own qualifier) is why there is no logic here that tries to
 * discover the newest alpha or rc -- a consumer names an exact version string and this enum records
 * which channel it belongs to. `pypackpack`'s own installer
 * (`dependency/backend/DefaultInterface.kt`'s `installPython`) hard-rejects every version except the
 * literal `"3.13"` today regardless of channel, so nothing here makes an alpha or rc actually
 * installable -- it only lets `toolchain` reject a malformed version string, and log which channel a
 * well-formed one names, before that string ever reaches `ppp`.
 */
enum class PythonReleaseChannel {
    ALPHA,
    RC,
    NORMAL,
}

/**
 * A parsed CPython version string, in the format `(플러그인예시)build.gradle.kts` actually writes
 * (`compileSdk = "3.11.9-alpha"`): `X.Y`, `X.Y.Z`, `X.Y.Z-alpha[N]`, or `X.Y.Z-rc[N]`, each with an
 * optional trailing `t` for the free-threaded build (`3.15t`).
 *
 * Deliberately a different grammar from `pypackpack`'s own `validatePythonVersion`
 * (`cli/.../CommandExtension.kt`'s `^\d+\.\d+(\.\d+)?$`), which has no channel suffix at all --
 * [toReleaseString] is the bridge: it strips the channel back off so the result is exactly what
 * `ppp`'s validator (and its `python use`/`install` commands) accept.
 */
data class PythonVersion(
    val major: Int,
    val minor: Int,
    val micro: Int,
    val channel: PythonReleaseChannel,
    val serial: Int? = null,
    /** The free-threaded build (PEP 703), spelled with a trailing `t`: `3.15t`, `3.15.0t`. */
    val freeThreaded: Boolean = false,
) {
    /** The `X.Y.Z` form `pypackpack`'s `validatePythonVersion` accepts, channel suffix stripped. */
    fun toReleaseString(): String = "$major.$minor.$micro"

    /**
     * The `X.Y.Z` or `X.Y.Zt` form `pypackpack`'s `BundleRequest.pythonVersion` takes, which fixes
     * the wheel tags (`3.15.0` is `cp315-cp315`, `3.15.0t` is `cp315-cp315t`).
     */
    fun toWheelVersion(): String = toReleaseString() + if (freeThreaded) "t" else ""

    override fun toString(): String =
        buildString {
            append(toReleaseString())
            when (channel) {
                PythonReleaseChannel.ALPHA -> append("-alpha${serial ?: ""}")
                PythonReleaseChannel.RC -> append("-rc${serial ?: ""}")
                PythonReleaseChannel.NORMAL -> Unit
            }
            if (freeThreaded) append("t")
        }

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:-(alpha|rc)(\d+)?)?(t)?$""")

        /** Throws [IllegalArgumentException] on anything that does not match the format above. */
        fun parse(raw: String): PythonVersion {
            val trimmed = raw.trim()
            val match =
                PATTERN.matchEntire(trimmed) ?: throw IllegalArgumentException(
                    "Invalid Python version '$raw'. Expected 'X.Y', 'X.Y.Z', 'X.Y.Z-alpha[N]', " +
                        "or 'X.Y.Z-rc[N]', each optionally followed by 't' (free-threaded).",
                )

            val (majorStr, minorStr, microStr, channelWord, serialStr, threadedStr) = match.destructured
            val major = majorStr.toInt()
            val minor = minorStr.toInt()
            val micro = microStr.toIntOrNull() ?: 0
            val serial = serialStr.toIntOrNull()

            val channel =
                when (channelWord) {
                    "alpha" -> PythonReleaseChannel.ALPHA
                    "rc" -> PythonReleaseChannel.RC
                    else -> PythonReleaseChannel.NORMAL
                }

            return PythonVersion(major, minor, micro, channel, serial, threadedStr == "t")
        }
    }
}
