import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.gradle.api.artifacts.ResolvedArtifact
import com.google.gson.Gson
import java.nio.file.Paths
import java.io.File
import java.nio.file.Path
import java.util.jar.JarFile
import org.objectweb.asm.*
import org.objectweb.asm.tree.*


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.chaquo.python)
}


group = "io.github.thisisthepy"
version = "1.0.0.0"


kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(projects.pycomposeui)
        }

        commonMain.dependencies {
            implementation(projects.pycomposeui)
        }
    }
}


chaquopy {
    defaultConfig {
        version = libs.versions.python.get()

        pip {
            // Use the local repository for the Python packages.
            options("--extra-index-url", "libs/pip/local")

            // Dependencies for the kotlin-python meta package.
            install("Deprecated")

            // Dependencies for the llama-cpp-python package.
            install("typing-extensions")
            install("numpy")
            install("diskcache")
            install("jinja2")
            install("MarkupSafe")
            install("llama-cpp-python")

            // Dependencies for the huggingface_hub package.
            install("PyYAML")
            install("huggingface_hub")

            // Dependencies for the jupyter package.
            install("pyzmq")
            install("rpds-py")
            install("argon2-cffi-bindings")
            install("jupyter")
        }
    }
    sourceSets {
        getByName("main") {
            srcDirs(
                "src/androidMain/python", "src/androidMain/generated/meta",
                "src/commonMain/python", "src/commonMain/generated/meta"
            )
        }
    }
}


android {
    namespace = "$group.pythonapptemplate"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        applicationId = "$group.pythonapptemplate"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = version.toString()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    dependencies {
        debugImplementation(compose.uiTooling)
    }
}


enum class DependencyType(private val type: String) {
    IMPLEMENTATION("Implementation"), API("Api");

    override fun toString(): String {
        return type
    }
}


fun Project.resolveDependenciesForPython(isSubModule: Boolean = false, sourceSetName: String? = null)
    : Triple<
        MutableMap<KotlinSourceSet, Pair<Path, MutableMap<DependencyType, List<ResolvedArtifact>>>>,
        MutableMap<KotlinSourceSet, MutableMap<DependencyType, List<UnresolvedDependency>>>,
        Boolean>
{
    val dependencyTree = mutableMapOf<KotlinSourceSet, Pair<Path, MutableMap<DependencyType, List<ResolvedArtifact>>>>()
    val unresolvedTree = mutableMapOf<KotlinSourceSet, MutableMap<DependencyType, List<UnresolvedDependency>>>()
    var isMetaJsonLockFileChanged = false

    // Build a dependency tree
    this.kotlin.sourceSets.forEach { sourceSet ->
        // Skip source set if sourceSetName is not null and does not match to the requested source set
        if (sourceSetName != null && sourceSet.name != sourceSetName) return@forEach

        val sourceSetDependencyTree = mutableMapOf<DependencyType, List<ResolvedArtifact>>()
        val sourceSetUnresolvedTree = mutableMapOf<DependencyType, List<UnresolvedDependency>>()

        // default source set
        val srcNameWithDefaults = sourceSet.kotlin.srcDirs.mapNotNull {
            if (it.toString().contains(Paths.get(sourceSet.name, "kotlin").toString())) it else null
        }
        // custom named source set (kotlin, java)
        val srcNameWithCustom = sourceSet.kotlin.srcDirs.mapNotNull {
            if (it.toString().endsWith(Paths.get("", "kotlin").toString())
                || it.toString().endsWith(Paths.get("", "java").toString())) it else null
        }
        val targetDir = srcNameWithDefaults.firstOrNull() ?: srcNameWithCustom.firstOrNull() ?: sourceSet.kotlin.srcDirs.first()
        val generationDir = Paths.get(targetDir.parent!!, "generated", "meta")

        dependencyTree[sourceSet] = Pair(generationDir, sourceSetDependencyTree)
        unresolvedTree[sourceSet] = sourceSetUnresolvedTree

        configurations {
            when (isSubModule) {
                false -> mapOf(
                    DependencyType.IMPLEMENTATION to getByName(sourceSet.implementationConfigurationName),
                    DependencyType.API to getByName(sourceSet.apiConfigurationName)
                )
                true -> mapOf(  // when the project is a submodule
                    DependencyType.API to getByName(sourceSet.apiConfigurationName)
                )
            }.forEach { (dependencyType, configuration) ->
                // Making configuration copy with isCanBeResolved = true
                val resolved = try {
                    create(configuration.name + "Resolved") {
                        extendsFrom(configuration)
                        isCanBeResolved = true
                    }
                } catch (ignored: Exception) { configurations.getByName(configuration.name + "Resolved") }
                val lenientConfiguration = resolved.resolvedConfiguration.lenientConfiguration

                // Adding dependencies to the tree
                val artifacts = lenientConfiguration.artifacts.toMutableList()
                sourceSetDependencyTree[dependencyType] = artifacts
                val unresolved = mutableListOf<UnresolvedDependency>()
                sourceSetUnresolvedTree[dependencyType] = unresolved

                // Handling unresolved dependencies
                lenientConfiguration.unresolvedModuleDependencies.forEach { unresolvedDependency ->
                    if (
                        unresolvedDependency.problem.message?.contains(
                            "project :${unresolvedDependency.selector.name}"
                        ) == true
                    ) {
                        val (submoduleDependency, raised, _) = project(":"+unresolvedDependency.selector.name)
                            .resolveDependenciesForPython(isSubModule = true, sourceSetName = sourceSet.name)
                        submoduleDependency.forEach { (key, value) ->
                            if (key.name == sourceSet.name) { artifacts.addAll(value.second[DependencyType.API]!!) }
                        }
                        raised.forEach { (key, value) ->
                            if (key.name == sourceSet.name) { unresolved.addAll(value[DependencyType.API]!!) }
                        }
                    } else {
                        unresolved.add(unresolvedDependency)
                    }
                }
            }
        }
    }

    if (!isSubModule) {
        val gson = Gson()
        val metaJsonDir = File(project.projectDir, Paths.get( "build", "kotlin-python").toString())
        metaJsonDir.mkdirs()
        val metaJsonLockFile = File(metaJsonDir, "meta_json.lock")
        val metaJsonNewFile = File(metaJsonDir, "meta_json.new")
        metaJsonLockFile.createNewFile()
        metaJsonNewFile.createNewFile()

        // Create build/kotlin-python/meta_json.new file
        metaJsonNewFile.writeText(gson.toJson(dependencyTree.map {
            it.key to mapOf(
                "directory" to it.value.first.toString(),
                "artifacts" to it.value.second.map { (type, artifacts) ->
                    type.toString() to artifacts.map { artifact ->
                        mapOf(
                            "name" to artifact.name,
                            "group" to artifact.moduleVersion.id.group,
                            "version" to artifact.moduleVersion.id.version,
                            "file" to artifact.file.absolutePath
                        )
                    }
                }.toMap()
            )
        }.associate { (sourceSet, dependencies) ->
            sourceSet.name to dependencies
        }))
        val metaJsonNew = gson.fromJson(metaJsonNewFile.readText(), Map::class.java)

        // Compare artifacts with build/kotlin-python/meta_json.lock
        if (metaJsonLockFile.exists()) {
            val metaJsonLock = gson.fromJson(metaJsonLockFile.readText(), Map::class.java)
            if (metaJsonLock != metaJsonNew) {
                isMetaJsonLockFileChanged = true

                // Replace build/kotlin-python/meta_json.lock with .new
                metaJsonLockFile.writeText(gson.toJson(metaJsonNew))
            }
        }
        metaJsonNewFile.delete()
    }
    return Triple(dependencyTree, unresolvedTree, isMetaJsonLockFileChanged)
}



fun processJarForMetaPackage(jarPath: String, outputDir: File) {
    JarFile(jarPath).use { jar ->
        val classNodes = mutableMapOf<String, ClassNode>()

        // First, read all classes from the JAR
        jar.entries().asSequence()
            .filter { it.name.endsWith(".class") }
            .forEach { entry ->
                val classReader = ClassReader(jar.getInputStream(entry))
                val node = ClassNode()
                classReader.accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                classNodes[node.name] = node
            }

        // Then, process only top-level classes
        classNodes.values.forEach { node ->
            if (!node.name.contains('$') && !node.access.hasFlag(Opcodes.ACC_PRIVATE) && !node.access.hasFlag(Opcodes.ACC_PROTECTED)) {
                val pyFile = createMetaPythonFile(node, outputDir)
                generateMetaPythonClass(node, pyFile, classNodes)
            }
        }
    }
}


fun createMetaPythonFile(node: ClassNode, outputDir: File): File {
    val packagePath = node.name.substringBeforeLast('/').replace('/', File.separatorChar)
    val className = node.name.substringAfterLast('/')
    val packageDir = File(outputDir, packagePath)
    packageDir.mkdirs()
    return File(packageDir, "$className.py")
}


fun generateMetaPythonClass(node: ClassNode, pyFile: File, allClasses: Map<String, ClassNode>) {
    val className = node.name.substringAfterLast('/')
    val nestedClasses = mutableMapOf<String, ClassNode>()

    pyFile.bufferedWriter().use { writer ->
        writer.write("from __future__ import annotations\n\n\n")
        writer.write("from deprecated import deprecated\n")
        writer.write("from java import *\n\n")
        writer.write("__metaclass__ = jclass(__name__)\n\n\n")

        if (node.visibleAnnotations?.any { it.desc == "Lkotlin/Deprecated;" } == true) {
            writer.write("@deprecated\n")
        }

        writer.write("class $className:\n")
        writer.write("    __meta__ = __metaclass__\n")
        writer.write("    __dict__ = __metaclass__.__dict__\n")
        writer.write("    __doc__ = __metaclass__.__doc__\n")
        writer.write("    __name__ = __metaclass__.__name__\n")

        val fields = node.fields
            .filter { !it.access.hasFlag(Opcodes.ACC_PRIVATE) && !it.access.hasFlag(Opcodes.ACC_PROTECTED) }
            .filter { !it.name.startsWith("\$stable") }
            .filter { !it.name.startsWith("Companion") }

        val methods = node.methods
            .filter { !it.access.hasFlag(Opcodes.ACC_PRIVATE) && !it.access.hasFlag(Opcodes.ACC_PROTECTED) }
            .filter { !it.name.startsWith("<") }

        generateMetaPythonClassFields(writer, fields, className)
        generateMetaPythonClassMethods(writer, methods)

        // Process nested classes
        node.innerClasses.forEach { innerClass ->
            if (innerClass.name.startsWith(node.name) && innerClass.name != node.name) {
                val nestedClassName = innerClass.name.substringAfterLast('$')
                allClasses[innerClass.name]?.let { nestedClassNode ->
                    nestedClasses[nestedClassName] = nestedClassNode
                }
            }
        }

        // Generate nested classes
        nestedClasses.forEach { (nestedClassName, nestedClassNode) ->
            // Skip anonymous classes
            if (nestedClassName.toIntOrNull() != null) return@forEach

            writer.write("\n    class $nestedClassName:\n")
            writer.write("        __meta__ = __metaclass__.$nestedClassName\n")
            writer.write("        __dict__ = __meta__.__dict__\n")
            writer.write("        __doc__ = __meta__.__doc__\n")
            writer.write("        __name__ = __meta__.__name__\n")

            val nestedFields = nestedClassNode.fields
                .filter { !it.access.hasFlag(Opcodes.ACC_PRIVATE) && !it.access.hasFlag(Opcodes.ACC_PROTECTED) }
                .filter { !it.name.startsWith("\$stable") }

            val nestedMethods = nestedClassNode.methods
                .filter { !it.access.hasFlag(Opcodes.ACC_PRIVATE) && !it.access.hasFlag(Opcodes.ACC_PROTECTED) }
                .filter { !it.name.startsWith("<") }

            generateMetaPythonClassFields(writer, nestedFields, "$className.$nestedClassName", "        ")
            generateMetaPythonClassMethods(writer, nestedMethods, "        ")
        }

        writer.write("\n\n__meta__ = __metaclass__\n__class__ = $className\n")
    }
}


fun generateMetaPythonClassFields(writer: java.io.BufferedWriter, fields: List<FieldNode>, className: String, indent: String = "    ") {
    fields.forEach { field ->
        val fieldName = field.name
        val fieldType = field.desc.toReadableType(if (className.contains(".")) {
            val index = className.indexOf('.')
            className.substring(index+1)
        } else {
            className
        })
        if (fieldName.contains("INSTANCE")) {
            writer.write("\n${indent}INSTANCE: $className = __meta__.INSTANCE\n")
        } else {
            writer.write("\n$indent$fieldName: $fieldType = __meta__.$fieldName\n")
        }
    }
}


fun generateMetaPythonClassMethods(writer: java.io.BufferedWriter, methods: List<MethodNode>, indent: String = "    ") {
    val methodGroups = methods.groupBy { it.name.substringBefore('$') }
    val nameSpace = mutableListOf<String>()

    methodGroups.forEach { (baseName, group) ->
        val hasAnnotations = group.any { it.name.contains('$') }
        if (hasAnnotations) {
            // TODO: Implement annotation processing
            //writer.write("\n${indent}@property\n")
            //if (group.any { it.visibleAnnotations?.any { anno -> anno.desc == "Landroidx/compose/runtime/Stable;" } == true }) {
            //    writer.write("\n${indent}@Stable\n")
            //}
            //writer.write("${indent}def $baseName(self):\n")
            //writer.write("${indent}    return __meta__.${group.first().name}\n")
        }
        if (group.first().name.startsWith("get")) {  // Property getter
            val methodName = baseName.substring(baseName.indexOf("get")+3).stripeDash()  // TODO: Check if removing dash does not break anything
            if (nameSpace.contains(methodName)) return@forEach
            writer.write("\n${indent}@property\n")
            writer.write("${indent}def $methodName(self):\n")
            writer.write("${indent}    return __meta__.${group.first().name.stripeDash()}()\n")
            nameSpace.add(methodName)
        //} else if (group.first().name.startsWith("set")) {  // Property setter
        //    val methodName = baseName.substring(baseName.indexOf("set")+3).stripeDash()
        //    if (nameSpace.contains(methodName)) return@forEach
        //    writer.write("\n${indent}@$methodName.setter\n")
        //    writer.write("${indent}def $methodName(self, *args, **kwargs):\n")
        //    writer.write("${indent}    __meta__.${group.first().name.stripeDash()}(*args, **kwargs)\n")
        //    nameSpace.add(methodName)
        } else {
            val converted = baseName.convertToSnakeCase().stripeDash()  // Replace Kotlin's special characters
            val pythonName = converted.convertToPythonMethodName()
            if (nameSpace.contains(pythonName)) return@forEach
            writer.write("\n${indent}def $pythonName(self, *args, **kwargs):\n")
            writer.write("${indent}    __meta__.${group.first().name.stripeDash()}(*args, **kwargs)\n")
            nameSpace.add(pythonName)
        }
    }
}


fun String.toReadableType(className: String): String {
    return when {
        this.startsWith("L") && this.endsWith(";") -> {
            val type = this.substring(1, this.length - 1).replace('/', '.')
            if (type.contains('$')) {
                val outerClass = type.substringBefore('$')
                val innerClass = type.substringAfter('$')
                if (outerClass == className) innerClass else type
            } else {
                type
            }
        }
        this == "I" -> "jint"
        this == "J" -> "jlong"
        this == "Z" -> "jboolean"
        this == "F" -> "jfloat"
        this == "D" -> "jdouble"
        this == "V" -> "jvoid"
        this.startsWith("[") -> this.substring(1).toReadableType(className) + "[]"
        else -> this
    }
}


fun Int.hasFlag(flag: Int): Boolean = (this and flag) != 0


fun String.stripeDash(): String = (if (this.contains("-")) this.substring(0, this.indexOf('-')) else this).trim()


fun String.convertToSnakeCase(): String = this.fold("") { acc, char ->
        if (char.isUpperCase()) {
            acc + "_" + char.lowercaseChar()
        } else {
            acc + char
        }
    }.trimStart('_')


fun String.convertToPythonMethodName(): String = when (this) {
        "from" -> "_from_"
        "global" -> "_global_"
        "import" -> "_import_"
        "lambda" -> "_lambda_"
        "nonlocal" -> "_nonlocal_"
        "raise" -> "_raise_"
        "try" -> "_try_"
        "with" -> "_with_"
        "yield" -> "_yield_"

        "to_string" -> "__str__"
        "to_int" -> "__int__"
        "to_float" -> "__float__"
        "to_bool" -> "__bool__"
        "to_bytes" -> "__bytes__"
        "to_list" -> "__list__"
        "to_tuple" -> "__tuple__"
        "to_set" -> "__set__"
        "to_frozenset" -> "__frozenset__"
        "to_dict" -> "__dict__"

        "add" -> "__add__"
        "sub" -> "__sub__"
        "mul" -> "__mul__"
        "truediv" -> "__truediv__"
        "floordiv" -> "__floordiv__"
        "mod" -> "__mod__"
        "pow" -> "__pow__"
        "equal" -> "__eq__"
        "equals" -> "__eq__"
        "hash" -> "__hash__"
        "hash_code" -> "__hash__"
        "length" -> "__len__"
        "copy" -> "__copy__"

        else -> this
    }


val createKotlinMetaPackageForPython by tasks.registering {
    val (dependencyTree, unresolvedTree, isChanged) = project.resolveDependenciesForPython()
    outputs.upToDateWhen {
        false//!isChanged
    }
    doLast {
        //if (!isChanged) return@doLast  // Skip if the meta_json.lock file is not changed

        // Print dependency tree
        dependencyTree.forEach { (sourceSet, dependencies) ->
            println("Dependencies for source set: ${sourceSet.name}")
            println("  Meta package generation directory: ${dependencies.first}")
            val unresolvedDependencies = unresolvedTree[sourceSet] ?: emptyMap()

            dependencies.second.forEach { (type, artifacts) ->
                val unresolved = unresolvedDependencies[type] ?: emptyList()

                println("  Configuration: ${sourceSet.name}$type")
                println("    Resolved artifacts:" + if (artifacts.isNotEmpty()) "" else " NONE")
                artifacts.forEach { artifact ->
                    println("      ${artifact.file.absolutePath}")
                }
                println("    Unresolved dependencies:" + if (unresolved.isNotEmpty()) "" else " NONE")
                unresolved.forEach { unresolvedDependency ->
                    println("      ${unresolvedDependency.selector}")
                    val message = unresolvedDependency.problem.message.toString()
                        .replace("    project", "            project")
                        .replace("Required by:", "          Required by:")
                    println("        - Problem: $message")
                }
            }
        }

        // Create meta package
        print("\nAssembling meta package for Kotlin source sets...")
        System.out.flush()
        dependencyTree.forEach { (_, dep) ->
            val artifacts = dep.second[DependencyType.IMPLEMENTATION]!! + dep.second[DependencyType.API]!!
            if (artifacts.isEmpty()) return@forEach  // Skip if there are no artifacts
            val metaPackageDir = File(dep.first.toString())
            if (metaPackageDir.exists()) {
                metaPackageDir.deleteRecursively()  // Clean the directory
            }
            metaPackageDir.mkdirs()  // Create the directory
            artifacts.forEach {
                processJarForMetaPackage(it.file.absolutePath, metaPackageDir)  // Process lib files
            }
        }
        println(" Done!")
    }
}

tasks.getByName("prepareKotlinIdeaImport").dependsOn(createKotlinMetaPackageForPython)
