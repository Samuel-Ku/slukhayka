import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.kover)
}

fun intProperty(name: String): Int? = providers.gradleProperty(name).orNull?.toIntOrNull()

kover {
  reports {
    total {
      html { onCheck = false }
      xml { onCheck = true }
      verify {
        rule {
          minBound(intProperty("kover.instructionThreshold") ?: 80)
        }
        rule {
          minBound(intProperty("kover.branchThreshold") ?: 70, coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
        }
      }
    }
  }
}

// wayfinder #47: export Room schemas into the repo so future migrations are
// reviewable and verifiable. KSP writes the JSON for the current database
// version on every build.
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.audiobook.read"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    // Debug uses AGP's default debug signing (auto-generated ~/.android/debug.keystore).
    // A custom signingConfig pointing at a gitignored ${rootDir}/debug.keystore was
    // removed — it broke every fresh checkout and CI run at :app:validateSigningDebug.
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Firebase Auth with Google Sign-In requires all of the following to be uncommented together.
  // If you are using Firebase Auth with other providers (e.g. Email/Password), you may only need
  // firebase-auth.
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  // spec-19 T3: on-device ONNX inference for the recommendation embedder
  // (ai.onnxruntime API). The Android artifact ships the model runner into
  // the app. The desktop (JVM) artifact is deliberately NOT a test
  // dependency — both jars contain the same ai.onnxruntime classes, so a
  // duplicate on the unit-test classpath would be ambiguous. The eval gate
  // script gets the desktop jar via its own configuration below.
  implementation(libs.onnxruntime.android)

// spec-19 T3: the reproducible eval gate — runRecommendationEval. A host
// JVM script (test sources, so it reuses the fixtures + RecommendationEval)
// that loads the downloaded ONNX model and prints recall@20/NDCG@20 for
// baseline vs semantic, writing the report. The desktop onnxruntime jar
// (with host natives) comes only from this configuration; the android AAR
// is filtered out of the script's classpath.
val evalRuntime: Configuration by configurations.creating

dependencies {
  add("evalRuntime", libs.onnxruntime.jvm)
}

// spec-19 T3: fetches the ONNX model (int8 multilingual-e5-small, ~118 MB)
// into app/src/main/assets/models/e5/. Not committed (GitHub's 100 MB
// file limit); the embedder degrades to the keyword baseline when absent.
val downloadE5Model by tasks.registering(Exec::class) {
  group = "verification"
  description = "Downloads the multilingual-e5-small int8 ONNX model + tokenizer into app/src/main/assets/models/e5"
  val assetsDir = file("src/main/assets/models/e5")
  val modelFile = assetsDir.resolve("model.onnx")
  doFirst {
    assetsDir.mkdirs()
    if (modelFile.exists()) {
      logger.lifecycle("model.onnx already present — skipping download")
    } else {
      logger.lifecycle("downloading multilingual-e5-small int8 ONNX (~118 MB)...")
    }
  }
  commandLine(
    "bash", "-c",
    """
      set -e
      mkdir -p "${'$'}(pwd)/src/main/assets/models/e5"
      if [ ! -f src/main/assets/models/e5/model.onnx ]; then
        curl -sL --max-time 1200 -o src/main/assets/models/e5/model.onnx \
          "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx"
      fi
      if [ ! -f src/main/assets/models/e5/tokenizer.json ]; then
        curl -sL --max-time 120 -o src/main/assets/models/e5/tokenizer.json \
          "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json"
      fi
    """.trimIndent()
  )
}

val runRecommendationEval by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Runs the spec-19 leave-one-out eval gate on saved fixtures with the real ONNX model; prints recall@20 / NDCG@20 and the GO/NO-GO decision."
  mainClass.set("com.example.data.recommend.RunRecommendationEval")
  // Classpath: test+main outputs, the desktop onnxruntime jar, and the
  // test runtime minus the android AAR (whose ai.onnxruntime classes share
  // the package — the desktop jar must win so its host natives load).
  // Compile the unit tests first (RunRecommendationEval lives in test
  // sources); its output dir carries the main class. The android onnxruntime
  // AAR is excluded from the runtime classpath so the desktop jar (with
  // host natives) resolves the ai.onnxruntime API.
  dependsOn("compileDebugUnitTestKotlin", "processDebugUnitTestJavaRes")
  val compileTask = project.tasks.named("compileDebugUnitTestKotlin")
  val testOutput = (compileTask.get() as org.jetbrains.kotlin.gradle.tasks.KotlinCompile).destinationDirectory
  val resourcesOutput = project.layout.buildDirectory.dir("intermediates/java_res/debugUnitTest/processDebugUnitTestJavaRes/out")
  val testCp = project.configurations.getByName("debugUnitTestRuntimeClasspath")
    .files.filter { !it.name.contains("onnxruntime-android") }
  classpath = project.files(
    project.configurations.getByName("evalRuntime"),
    testOutput,
    resourcesOutput
  ) + project.files(testCp)
  // The model+tokenizer assets live under app/src/main/assets/models/e5;
  // the script resolves them relative to the project dir.
  args("${projectDir}/src/main/assets/models/e5")
  isIgnoreExitValue = false
}
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.core)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.kotlinx.coroutines.core)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
