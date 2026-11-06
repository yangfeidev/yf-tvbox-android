import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "me.bsdnme.tvbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.bsdnme.tvbox"
        // 21 起 ExoPlayer 与 Compose 均可运行；24 起省掉一批兼容分支，
        // 老盒子（4.4）如果需要支持再单独降级评估
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        // 电视与手机同一个包，靠运行时分流布局
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        create("release") {
            // 密钥信息放 local.properties（已被 .gitignore 排除），不进仓库
            val props = Properties()
            val file = rootProject.file("local.properties")
            if (file.exists()) props.load(file.inputStream())
            val storeFilePath = props.getProperty("release.storeFile")
            if (storeFilePath != null) {
                // 路径以工程根目录为基准（local.properties 里写的是 keystore/feige-release.jks）
                storeFile = rootProject.file(storeFilePath)
                storePassword = props.getProperty("release.storePassword")
                keyAlias = props.getProperty("release.keyAlias")
                keyPassword = props.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 签名信息从 local.properties 读取（见 signingConfigs），
            // 没配置时不指定签名，产出未签名包
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // 图标全集：core 只带十几个基础图标，播放器与导航要用的 SkipNext / LiveTv 等都不在里面
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
