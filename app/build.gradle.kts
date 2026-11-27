/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import com.android.ddmlib.AndroidDebugBridge
import java.util.Properties
import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
}

protobuf {
  protoc {
    val protocDep: MinimalExternalModuleDependency = libs.protobuf.protoc.get()
    artifact = "${protocDep.group}:${protocDep.name}:${protocDep.version}"
  }

  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        id("kotlin")
        id("java") {}
      }
    }
  }
}

val (projectVersionName, projectVersionCode) = readVersionProperties(project)

android {
  namespace = "org.tfv.deskflow"
  buildToolsVersion = "36.0.0"
  compileSdk = 36

  defaultConfig {
    // Own application id (code review D18): the upstream id "org.tfv.deskflow" collided with
    // the original app. The Kotlin package/namespace stays org.tfv.deskflow.
    applicationId = "de.juliandigitalproducts.deskflow"
    minSdk = 34
    targetSdk = 36

    versionCode = projectVersionCode
    versionName = projectVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables.useSupportLibrary = true

    ndk { abiFilters += listOf("arm64-v8a") }
  }

  // Release signing (code review D2, same model as EV Trigger Flows/BlackMyData): DESKFLOW_KEYSTORE_* env vars
  // (CI secrets) or ../deskflow-release.keystore.properties next to this repository. Without a
  // key the release build stops instead of silently signing with the public debug key – anyone with
  // that key could ship an "update" that keeps the accessibility permission.
  val keyProps = Properties()
  rootProject.file("../deskflow-release.keystore.properties").takeIf { it.exists() }
    ?.inputStream()?.use { keyProps.load(it) }
  fun releaseKey(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keyProps.getProperty(prop)
  val releaseStoreFile = releaseKey("DESKFLOW_KEYSTORE_FILE", "storeFile")
  // Shared debug key: debug builds only, so debug builds from different machines update each other.
  // It is a public key (releases up to v1.0.15 were wrongly signed with it) and is not published on
  // GitHub; without the file, debug builds use the standard Android debug keystore (~/.android/debug.keystore).
  val sharedDebugKeystore = file("deskflow-shared-debug.keystore")
  signingConfigs {
    if (sharedDebugKeystore.exists()) {
      getByName("debug") {
        storeFile = sharedDebugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
    if (!releaseStoreFile.isNullOrBlank()) {
      create("release") {
        storeFile = file(releaseStoreFile)
        storePassword = releaseKey("DESKFLOW_KEYSTORE_PASSWORD", "storePassword")
        keyAlias = releaseKey("DESKFLOW_KEY_ALIAS", "keyAlias")
        keyPassword = releaseKey("DESKFLOW_KEY_PASSWORD", "keyPassword")
      }
    }
  }
  if (releaseStoreFile.isNullOrBlank()) {
    gradle.taskGraph.whenReady {
      if (allTasks.any { it.name.contains("Release") && (it.name.startsWith("package") || it.name.startsWith("bundle")) }) {
        throw GradleException(
          "No Deskflow release key: set DESKFLOW_KEYSTORE_FILE/_PASSWORD, DESKFLOW_KEY_ALIAS/_PASSWORD " +
            "or create ../deskflow-release.keystore.properties (see README → Release signing)."
        )
      }
    }
  }
  buildFeatures {
    buildConfig = true
    compose = true
    viewBinding = true
    aidl = true
  }

  buildTypes {
    debug {
      isDebuggable = true
      isMinifyEnabled = false
      buildConfigField("boolean", "DEBUG", "true")
    }
    release {
      if (!releaseStoreFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
      // R8 on (code review D23); keep rules for reflection/IPC in proguard-rules.pro.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      buildConfigField("boolean", "DEBUG", "false")
    }

    // applicationVariants.all {
    //   outputs.all {
    //     if (this is com.android.build.gradle.api.ApkVariant) {
    //       val aabOutputFile =
    //         File(
    //           outputDirectory,
    //           "${name}_${versionName}_${versionCode}_${buildType.name}.aab"
    //         )
    //       // Check if the task already exists to avoid errors during re-syncs
    //       if (tasks.findByName("rename${name.capitalize()}Aab") == null) {
    //         val renameAabTask =
    // tasks.register("rename${name.capitalize()}Aab") {
    //           doLast {
    //             val sourceFile = outputFile
    //             if (sourceFile.exists()) {
    //               sourceFile.copyTo(aabOutputFile, overwrite = true)
    //             } else {
    //               println("AAB file not found: ${sourceFile.absolutePath}")
    //             }
    //           }
    //         }
    //         dependsOn(renameAabTask)
    //       }
    //     }
    //   }
    // }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  // F-Droid rejects the encrypted dependency block in the APK signing block.
  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  sourceSets { getByName("main") { proto { srcDir("src/main/proto") } } }

  lint { disable.add("NullSafeMutableLiveData") }
}

dependencies {
  implementation(project(":client")) { exclude(group = "io.github.oshai") }

  implementation(libs.arrow.core)
  implementation(libs.arrow.fx.coroutines)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.logging.android)
  implementation(libs.slf4j.api)
  implementation(libs.protobuf.java.runtime)
  implementation(libs.protobuf.java.util)

  implementation(libs.protobuf.kotlin)


  implementation(libs.google.accompanist.permissions)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.normal)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.android)
  implementation(libs.androidx.lifecycle.runtime.normal)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.livedata.ktx)
  implementation(libs.androidx.lifecycle.livedata.normal)
  implementation(libs.androidx.lifecycle.livedata.core.normal)
  implementation(libs.androidx.lifecycle.livedata.core.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.normal)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.material3)

  implementation(libs.material)
  implementation(libs.androidx.constraintlayout)

  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register("installDebugEnableAccessibilityService") {
  dependsOn("installDebug")
  val ops = project.objects.newInstance<InjectedOps>()
  val execOps: ExecOperations = ops.execOps

  val appId = android.defaultConfig.applicationId!!
  doLast {
    val adbPath = "${android.sdkDirectory}/platform-tools/adb"

    runCatching {
      AndroidDebugBridge.terminate()
      AndroidDebugBridge.init(true)
    }
    val adb =
      AndroidDebugBridge.createBridge(
        adbPath,
        true,
        1000L,
        TimeUnit.MILLISECONDS,
      )
    adb.startAdb(1000L, TimeUnit.MILLISECONDS)
    println("ADB server started: ${adb.isConnected}")

    require(adb.devices.isNotEmpty()) {
      "At least one device must be connected to install app & enable the AccessibilityService."
    }

    adb.devices.forEach { device ->
      println(
        "Activating AccessibilityService on Device ${device.serialNumber}"
      )
      if (!device.isOnline) {
        println("Device ${device.serialNumber} is not online. Skipping...")
        return@forEach
      }

      execOps.exec {
        commandLine(
          adbPath,
          "-s",
          device.serialNumber,
          "shell",
          // Append instead of replacing the list of enabled accessibility services.
          ADD_A11Y_SERVICE_SCRIPT.replace("APP_ID", appId),
        )
      }
      execOps.exec {
        commandLine(
          adbPath,
          "-s",
          device.serialNumber,
          "shell",
          "am",
          "start",
          "-n",
          "$appId/org.tfv.deskflow.ui.activities.RootActivity",
        )
      }
    }

    runCatching { AndroidDebugBridge.terminate() }
  }
}

class RenameAABAction : Action<Task> {
  override fun execute(task: Task) {
    val isBundle = task.name.startsWith("bundle")
    val isAssemble = task.name.startsWith("assembleRelease")
    if ((isAssemble || isBundle) && !task.name.contains("Rename")) {
      val (outputFolder, ext, flavor) =
        when {
          isBundle ->
            Triple(
              "bundle",
              "aab",
              task.name.substring("bundle".length).lowercase(),
            )
          else ->
            Triple(
              "apk",
              "apk",
              task.name.substring("assemble".length).lowercase(),
            )
        }
      val renameTaskName = "${task.name}Rename${ext.uppercase()}"
      val layout = project.layout
      val buildDir = layout.buildDirectory.asFile.get().absolutePath
      val bundlePath = "${buildDir}/outputs/${outputFolder}/${flavor}/"
      val readyPath =
        file("${buildDir}/outputs/${outputFolder}-ready/${flavor}/")
      // Signed builds are written as app-<flavor>.apk, unsigned ones as app-<flavor>-unsigned.apk.
      val defaultAppBundleFilenames =
        if (isAssemble) listOf("app-${flavor}.$ext", "app-${flavor}-unsigned.$ext") else listOf("app-${flavor}.$ext")
      val appBundleFilename = "Deskflow-Android-Enhanced-$projectVersionName.$ext"
      readyPath.mkdirs()

      tasks.register<Copy>(renameTaskName) {
        from(bundlePath)
        include(defaultAppBundleFilenames)
        destinationDir = readyPath
        rename { appBundleFilename }
      }

      task.finalizedBy(renameTaskName)
    }
  }
}

tasks.whenTaskAdded(RenameAABAction())

private val ADD_A11Y_SERVICE_SCRIPT =
  "svc=APP_ID/org.tfv.deskflow.services.GlobalInputService; " +
    "cur=$(settings get secure enabled_accessibility_services); " +
    "case \":\$cur:\" in *\":\$svc:\"*) ;; *) " +
    "if [ -z \"\$cur\" ] || [ \"\$cur\" = null ]; then new=\$svc; else new=\"\$cur:\$svc\"; fi; " +
    "settings put secure enabled_accessibility_services \"\$new\" ;; esac; " +
    "settings put secure accessibility_enabled 1"
