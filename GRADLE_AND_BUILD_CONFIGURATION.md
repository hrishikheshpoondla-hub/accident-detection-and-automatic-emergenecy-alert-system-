# GRADLE AND BUILD CONFIGURATION

## 🛠 Optimized build.gradle.kts (App)

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("com.google.ar.sceneform:core:1.17.1")
    // LeakCanary for development
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
}
```

## ⚙️ gradle.properties
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.enableJetifier=true
android.nonTransitiveRClass=true
```

## 🔍 ProGuard Rules
Keep these rules in `app/proguard-rules.pro`:
```pro
-keep class com.google.ar.sceneform.** { *; }
-dontwarn com.google.ar.sceneform.**
-keep class com.google.android.gms.location.** { *; }
```
