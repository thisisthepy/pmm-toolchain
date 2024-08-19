package org.thisisthepy.toolchain.kotlin.meta

import kotlinx.metadata.jvm.KotlinClassHeader
import kotlinx.metadata.jvm.KotlinClassMetadata
import org.objectweb.asm.ClassReader
import java.io.File
import java.util.jar.JarFile


fun main(args: Array<String>) {
    if (args.size != 2) {
        println("Usage: DecompileKotlinMetadata <input.jar> <output.py>")
        return
    }

    val jarFile = File(args[0])
    val outputFile = File(args[1])

    JarFile(jarFile).use { jar ->
        val entries = jar.entries()
        val pythonMetaCode = StringBuilder()

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()

            if (entry.name.endsWith(".class")) {
                jar.getInputStream(entry).use { inputStream ->
                    val classReader = ClassReader(inputStream)
                    val classNode = classReader.acceptToKotlinMetadata()

                    if (classNode != null) {
                        println(classNode)

                        try {
                            val kotlinCode = generatePythonMetaCodeFromKotlinMetadata(classNode)
                            pythonMetaCode.append(kotlinCode).append("\n")
                        } catch (e: Exception) {
                            println("Error processing class ${entry.name}: ${e.message}")
                        }
                    }
                }
            }
        }

        outputFile.writeText(pythonMetaCode.toString())
        println("Python meta code has been written to ${outputFile.absolutePath}")
    }
}

fun ClassReader.acceptToKotlinMetadata(): KotlinClassMetadata? {
    val classVisitor = KotlinClassVisitor()
    this.accept(classVisitor, 0)
    val header = classVisitor.getKotlinClassHeader()

    // Ensure data1 is not empty before proceeding
    return if (header != null && !header.data1.isNullOrEmpty()) {
        try {
            KotlinClassMetadata.read(header)
        } catch (e: Exception) {
            println("Error reading Kotlin metadata: ${e.message}")
            null
        }
    } else {
        null
    }
}

fun generatePythonMetaCodeFromKotlinMetadata(metadata: KotlinClassMetadata): String {
    val pythonCodeBuilder = StringBuilder()

    println(metadata)
    when (metadata) {
        is KotlinClassMetadata.Class -> {
            val classData = metadata.toKmClass()
            pythonCodeBuilder.append("class ${classData.name}:\n")
            pythonCodeBuilder.append("    def __init__(self):\n")
            pythonCodeBuilder.append("        pass\n")
            classData.functions.forEach { func ->
                pythonCodeBuilder.append("    def ${func.name}(self):\n")
                pythonCodeBuilder.append("        pass\n")
            }
        }
        is KotlinClassMetadata.FileFacade -> {
            val packageData = metadata.toKmPackage()
            packageData.functions.forEach { func ->
                pythonCodeBuilder.append("def ${func.name}():\n")
                pythonCodeBuilder.append("    pass\n")
            }
        }
        is KotlinClassMetadata.MultiFileClassPart -> {
            val partData = metadata.toKmPackage()
            partData.functions.forEach { func ->
                pythonCodeBuilder.append("def ${func.name}():\n")
                pythonCodeBuilder.append("    pass\n")
            }
        }
        is KotlinClassMetadata.MultiFileClassFacade -> {
            pythonCodeBuilder.append("# MultiFileClassFacade: No additional data\n")
        }
        is KotlinClassMetadata.SyntheticClass -> {
            pythonCodeBuilder.append("# SyntheticClass: No additional data\n")
        }
        is KotlinClassMetadata.Unknown -> {
            pythonCodeBuilder.append("# Unknown: No additional data\n")
        }
        else -> {
            pythonCodeBuilder.append("# Unhandled Kotlin metadata type\n")
        }
    }

    return pythonCodeBuilder.toString()
}

class KotlinClassVisitor : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
    private var header: KotlinClassHeader? = null

    override fun visitAnnotation(descriptor: String?, visible: Boolean): org.objectweb.asm.AnnotationVisitor? {
        if (descriptor == "Lkotlin/Metadata;") {
            return KotlinMetadataAnnotationVisitor()
        }
        return super.visitAnnotation(descriptor, visible)
    }

    inner class KotlinMetadataAnnotationVisitor : org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
        private val annotationValues = mutableMapOf<String, Any?>()

        override fun visit(name: String?, value: Any?) {
            name?.let { annotationValues[it] = value }
        }

        override fun visitEnd() {
            header = KotlinClassHeader(
                kind = annotationValues["k"] as? Int,
                metadataVersion = annotationValues["mv"] as? IntArray,
                data1 = annotationValues["d1"] as? Array<String>,
                data2 = annotationValues["d2"] as? Array<String>,
                extraString = annotationValues["xs"] as? String,
                packageName = annotationValues["pn"] as? String,
                extraInt = annotationValues["xi"] as? Int
            )
        }
    }

    fun getKotlinClassHeader(): KotlinClassHeader? = header
}
