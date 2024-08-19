group = rootProject.group
version = rootProject.version


plugins {
    `kotlin-dsl`
    `maven-publish`
    `java-gradle-plugin`
}

dependencies {
    //implementation(gradleApi())
    //implementation(localGroovy())
    //implementation("org.jetbrains.kotlin:kotlin-stdlib")
    
    implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.5.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.ow2.asm:asm-util:9.4")
    implementation("org.ow2.asm:asm:9.4")
}

gradlePlugin {
    plugins {
        create("toolchain") {
            id = group
            implementationClass = "$id.toolchain.PythonPlugin"
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}
