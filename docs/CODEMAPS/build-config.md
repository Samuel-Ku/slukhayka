# Build & Config Module

<!-- Generated: 2026-07-30 | Files scanned: 11 | Token estimate: ~700 -->

## Purpose

All build configuration: Gradle scripts (root + app), version catalog, Android manifest, ProGuard rules, and security/persistence XML configs.

## Key Files

```
build.gradle.kts                              (root, plugins apply false)
settings.gradle.kts                           (project settings)
gradle.properties                             (JVM args, Kotlin style, parallel)
gradle/libs.versions.toml                     (version catalog)
gradle/wrapper/gradle-wrapper.properties      (Gradle 9.3.1)
app/build.gradle.kts                          (Android config + deps)
app/proguard-rules.pro                        (ProGuard / R8 rules)
app/src/main/AndroidManifest.xml
app/src/main/res/xml/network_security_config.xml
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
```

## Toolchain Versions

| Tool | Version | Notes |
|---|---|---|
| Gradle | 9.3.1 | Min required by AGP 9.1.1 |
| AGP | 9.1.1 | Android Gradle Plugin |
| Kotlin | 2.2.10 | With Compose Compiler plugin |
| KSP | 2.3.5 | For Room + Moshi codegen |
| compileSdk | 36 (minor 1) | Android 16 |
| targetSdk | 36 | |
| minSdk | 24 | Android 7.0 |
| JDK | 21 (build), 11 (source compat) | |
| Compose BOM | 2024.09.00 | |
| Media3 (ExoPlayer) | 1.3.1 | |

## Version Catalog Highlights (libs.versions.toml)

```toml
[versions]
agp = "9.1.1"
kotlin = "2.2.10"
media3 = "1.3.1"
room = "2.7.0"
composeBom = "2024.09.00"

[plugins]
android-application
kotlin-compose
google-devtools-ksp
roborazzi            # screenshot testing
secrets              # .env → BuildConfig
google-services      # Firebase (warns if google-services.json missing)
```

## Signing Configurations

```kotlin
signingConfigs {
  release { ... uses KEYSTORE_PATH env or my-upload-key.jks ... }
  debugConfig { storeFile = file("${rootDir}/debug.keystore") ... }
}
buildTypes {
  release { signingConfig = signingConfigs.getByName("release") }
  debug { signingConfig = signingConfigs.getByName("debugConfig") }
}
```

Note: `debug.keystore` is gitignored. For local builds, generate one with keytool.

## Key gradle.properties

```
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.workers.max=4
kotlin.compiler.execution.strategy=in-process
android.nonTransitiveRClass=true
googleServices.missing.passthrough=true   # build succeeds without google-services.json
```

## Security / Persistence XML

| File | Purpose |
|---|---|
| `network_security_config.xml` | Cleartext traffic policy, certificate pinning (if any) |
| `backup_rules.xml` | Auto-backup include/exclude rules |
| `data_extraction_rules.xml` | Android 12+ backup + device-transfer rules |

## Secrets Gradle Plugin

Reads `.env` (gitignored) at build time, exposes values as `BuildConfig` fields.
Falls back to `.env.example` for read-only defaults.

`.env.example` is committed and shows expected keys.

## Known Issues (Phase 2 candidates)

- `proguard-rules.pro` exists but `isMinifyEnabled = false` in release — ProGuard is configured but not used
- Hardcoded keystore password "android" in debugConfig — typical but worth documenting
- No CI / no GitHub Actions workflows
- `google-services` plugin present but `google-services.json` not committed (warns at build) — Firebase won't work without it
- Several unused dependencies commented out (`accompanist-permissions`, `androidx-camera-*`, `firebase-auth`, `play-services-location`, `androidx-datastore-preferences`) — consider cleaning up
- No dependency vulnerability scanning (dependabot / renovate)
- JDK sourceCompatibility = 11, but Kotlin 2.2.10 features available — verify Java target is intentional
