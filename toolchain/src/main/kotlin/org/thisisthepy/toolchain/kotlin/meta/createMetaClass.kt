package org.thisisthepy.toolchain.kotlin.meta

import java.io.File
import java.util.jar.JarFile
import org.objectweb.asm.*
import org.objectweb.asm.tree.*


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
