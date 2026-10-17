package org.thisisthepy.python.multiplatform.toolchain.typedpython

import groovy.json.JsonSlurper
import java.io.File

/**
 * Where the TypedPython gate comes from by default (#90, python-multiplatform #20): the `typedpython-gate`
 * zip that python-multiplatform-ksp publishes beside its processor jar. Its version is the
 * python-multiplatform project version (python-multiplatform-gradle-plugin's `DEFAULT_PROCESSOR_COORDINATES`
 * uses the same). This is the one place both are written; `-Ptypedpython.gate=<group:artifact:version>`
 * overrides it.
 */
const val DEFAULT_TYPEDPYTHON_GATE = "io.github.thisisthepy:python-multiplatform-ksp:3.13.0"

/** Project property overriding [DEFAULT_TYPEDPYTHON_GATE]. */
const val TYPEDPYTHON_GATE_PROPERTY = "typedpython.gate"

const val TYPEDPYTHON_GATE_CLASSIFIER = "typedpython-gate"

/** The manifest at the zip's root, beside the wheel. */
const val TYPEDPYTHON_GATE_MANIFEST = "typedpython-gate.json"

/** `group:artifact:version` -> the dependency notation of its `typedpython-gate` zip. */
fun typedpythonGateNotation(coordinates: String): String {
    val parts = coordinates.trim().split(':')
    require(parts.size == 3 && parts.all { it.isNotBlank() }) {
        "-P$TYPEDPYTHON_GATE_PROPERTY must be group:artifact:version, not '$coordinates'."
    }
    return "${parts.joinToString(":")}:$TYPEDPYTHON_GATE_CLASSIFIER@zip"
}

/** `typedpython-gate.json`, format 1. Every hash is a lower-case hex SHA-256. */
data class TypedpythonGateManifest(
    val typedpythonVersion: String,
    val typedpythonWheel: String,
    val typedpythonSha256: String,
    val pyreflyVersion: String,
    /** pyrefly's PyPI wheel file name -> its SHA-256, one per platform. */
    val pyreflySha256: Map<String, String>,
)

private val SHA256 = Regex("[0-9a-f]{64}")

fun parseTypedpythonGateManifest(json: String): TypedpythonGateManifest {
    val root = JsonSlurper().parseText(json) as? Map<*, *>
        ?: throw IllegalArgumentException("$TYPEDPYTHON_GATE_MANIFEST is not a JSON object.")
    require(root["format"]?.toString() == "1") {
        "$TYPEDPYTHON_GATE_MANIFEST has format '${root["format"]}'; this toolchain reads format 1."
    }
    val typedpython = root["typedpython"] as? Map<*, *>
        ?: throw IllegalArgumentException("$TYPEDPYTHON_GATE_MANIFEST has no 'typedpython' entry.")
    val pyrefly = root["pyrefly"] as? Map<*, *>
        ?: throw IllegalArgumentException("$TYPEDPYTHON_GATE_MANIFEST has no 'pyrefly' entry.")

    fun text(map: Map<*, *>, key: String, owner: String): String =
        (map[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$TYPEDPYTHON_GATE_MANIFEST: '$owner.$key' is missing.")

    fun hash(value: String, what: String): String {
        require(SHA256.matches(value)) { "$TYPEDPYTHON_GATE_MANIFEST: $what is not a SHA-256 ('$value')." }
        return value
    }

    val pyreflyHashes = (pyrefly["sha256"] as? Map<*, *>).orEmpty()
        .map { (file, sha) -> file.toString() to hash(sha?.toString().orEmpty(), "the hash of $file") }
        .toMap()
    require(pyreflyHashes.isNotEmpty()) { "$TYPEDPYTHON_GATE_MANIFEST: 'pyrefly.sha256' lists no wheel." }
    return TypedpythonGateManifest(
        typedpythonVersion = text(typedpython, "version", "typedpython"),
        typedpythonWheel = text(typedpython, "wheel", "typedpython"),
        typedpythonSha256 = hash(text(typedpython, "sha256", "typedpython"), "typedpython.sha256"),
        pyreflyVersion = text(pyrefly, "version", "pyrefly"),
        pyreflySha256 = pyreflyHashes,
    )
}

/**
 * The `--require-hashes` requirements for the gate. typedpython is a direct reference to the wheel in
 * [gateDir], so no index is ever asked for that name (unclaimed on PyPI); pyrefly comes from PyPI,
 * accepted only if it matches one of the manifest's hashes.
 */
fun typedpythonGateRequirements(manifest: TypedpythonGateManifest, gateDir: File): String {
    val wheel = File(gateDir, manifest.typedpythonWheel)
    check(wheel.isFile) {
        "The typedpython-gate zip names ${manifest.typedpythonWheel} in $TYPEDPYTHON_GATE_MANIFEST but does not contain it."
    }
    val pyreflyHashes = manifest.pyreflySha256.values.distinct().joinToString(" ") { "--hash=sha256:$it" }
    return "typedpython @ ${wheel.absoluteFile.toPath().toUri()} --hash=sha256:${manifest.typedpythonSha256}\n" +
        "pyrefly==${manifest.pyreflyVersion} $pyreflyHashes\n"
}

/** Everything comes from [requirements], and every distribution must match a hash in it. */
fun typedpythonHashedInstallCommand(venv: File, requirements: File): List<String> =
    listOf("uv", "pip", "install", "--python", venv.path, "--require-hashes", "-r", requirements.path)

/** The manifest's pyrefly wheel for this OS and architecture, or `null` when there is none. */
fun pyreflyWheelForHost(manifest: TypedpythonGateManifest, osName: String, osArch: String): String? {
    val arm = osArch.lowercase() in setOf("aarch64", "arm64")
    val x64 = osArch.lowercase() in setOf("x86_64", "amd64")
    val os = osName.lowercase()
    val matches: (String) -> Boolean = when {
        os.startsWith("mac") && arm -> { name -> "macosx" in name && name.endsWith("_arm64.whl") }
        os.startsWith("mac") && x64 -> { name -> "macosx" in name && name.endsWith("_x86_64.whl") }
        os.startsWith("linux") && arm -> { name -> "manylinux" in name && "_aarch64" in name }
        os.startsWith("linux") && x64 -> { name -> "manylinux" in name && "_x86_64" in name }
        os.startsWith("windows") && arm -> { name -> name.endsWith("win_arm64.whl") }
        os.startsWith("windows") && x64 -> { name -> name.endsWith("win_amd64.whl") }
        else -> return null
    }
    return manifest.pyreflySha256.keys.sorted().firstOrNull(matches)
}

fun pyreflyNoWheelMessage(manifest: TypedpythonGateManifest, osName: String, osArch: String): String =
    "typedpythonCheck: pyrefly ${manifest.pyreflyVersion} has no wheel for this platform ($osName $osArch) " +
        "among the hashes in the typedpython-gate manifest (${manifest.pyreflySha256.keys.sorted().joinToString()}). " +
        "Pass -P$TYPEDPYTHON_WHEEL_DIR_PROPERTY=<directory> holding a typedpython and a pyrefly wheel for this platform."
