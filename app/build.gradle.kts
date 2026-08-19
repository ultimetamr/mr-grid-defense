import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val releaseKeystoreProperties = Properties()
val releaseKeystoreFile = rootProject.file("keystore.properties")
if (releaseKeystoreFile.isFile) {
    releaseKeystoreFile.inputStream().use(releaseKeystoreProperties::load)
}
val hasReleaseSigning =
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !releaseKeystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.picoxr.mrspacetowerdefense"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.picoxr.mrspacetowerdefense"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add("arm64-v8a") }
        buildConfigField("int", "TARGET_SPATIAL_FPS", "90")
        buildConfigField("int", "MEMORY_BUDGET_MB", "500")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        jniLibs.useLegacyPackaging = false
    }
    androidResources {
        // GPU-ready KTX2/ETC2 payloads must remain byte-identical in the APK.
        noCompress += setOf("ktx2", "glb")
        // Unused scaffold USDZ embeds a PNG and is intentionally excluded from production packages.
        ignoreAssetsPattern = "box.usdz"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.spatial.bom))
    implementation(libs.spatial.core)
    implementation(libs.spatial.ui.platform)
    implementation(libs.spatial.ui.foundation)
    implementation(libs.spatial.ui.design)
    implementation(libs.spatial.ui.sense)
    implementation(libs.spatial.ui.tracking)
    implementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling.preview)
}

configurations.all {
    resolutionStrategy {
        exclude("androidx.compose.ui", "ui")
        exclude("androidx.compose.ui", "ui-graphics")
        exclude("androidx.compose.ui", "ui-text")
        exclude("androidx.compose.foundation", "foundation")
    }
}

val verifyTextureBudgets by tasks.registering {
    group = "verification"
    description = "Requires ETC2 KTX2 textures and enforces world/UI dimension budgets."
    val textureDirectory = layout.projectDirectory.dir("src/main/assets/textures")
    if (textureDirectory.asFile.exists()) inputs.dir(textureDirectory)
    doLast {
        val root = textureDirectory.asFile
        if (!root.exists()) {
            logger.lifecycle("No gameplay texture assets found; procedural ECS rendering needs no ETC2 conversion.")
            return@doLast
        }
        root.walkTopDown().filter { it.isFile }.forEach { texture ->
            check(texture.extension.equals("ktx2", ignoreCase = true)) {
                "Texture must be ETC2/KTX2, not ${texture.extension}: ${texture.relativeTo(root)}"
            }
            val header = texture.inputStream().use { it.readNBytes(28) }
            check(header.size == 28) { "Invalid KTX2 header: ${texture.relativeTo(root)}" }
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val vkFormat = buffer.getInt(12)
            val width = buffer.getInt(20)
            val height = buffer.getInt(24)
            check(vkFormat in 147..156) {
                "KTX2 must contain an ETC2/EAC Vulkan format (147..156): ${texture.relativeTo(root)}"
            }
            val relative = texture.relativeTo(root).invariantSeparatorsPath
            val maxDimension = if (relative.startsWith("ui/")) 512 else 1024
            check(width in 1..maxDimension && height in 1..maxDimension) {
                "$relative is ${width}x$height; limit is ${maxDimension}x$maxDimension"
            }
        }
    }
}

val verifyMonsterModels by tasks.registering {
    group = "verification"
    description = "Requires one valid GLB asset for every monster type."
    val expectedNames =
        listOf(
            "monster_normal.glb",
            "monster_fast.glb",
            "monster_armored.glb",
            "monster_self_destruct.glb",
            "monster_acid.glb",
            "monster_elite.glb",
            "monster_boss.glb",
        )
    val modelDirectory = layout.projectDirectory.dir("src/main/assets/models/monsters")
    inputs.files(expectedNames.map { modelDirectory.file(it) })
    doLast {
        expectedNames.forEach { name ->
            val model = modelDirectory.file(name).asFile
            check(model.isFile) { "Missing monster model: ${model.absolutePath}" }
            val magic = model.inputStream().use { it.readNBytes(4) }
            check(magic.contentEquals(byteArrayOf(0x67, 0x6c, 0x54, 0x46))) {
                "Monster model is not a binary glTF file: ${model.absolutePath}"
            }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(verifyTextureBudgets, verifyMonsterModels) }
