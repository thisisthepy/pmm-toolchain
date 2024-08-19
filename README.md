# Toolchain

Python Multiplatform Build Plugin/Tool with Kotlin Multiplatform Mobile

## How to run the project

#### Publish toolchain to local maven repository
```shell
./gradlew :toolchain:publishToMavenLocal
```

#### Run the Android app
##### 1. Enable 'app' module from settings.gradle.kts
```kts
include(":app")  // Uncomment this line
```
##### 2. Refresh Gradle project

##### 3. Run the app from Android Studio
