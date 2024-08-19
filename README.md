This is a Kotlin Multiplatform project targeting Android.

* `/composeApp` is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - `commonMain` is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    `iosMain` would be the right folder for such calls.


Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…


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
