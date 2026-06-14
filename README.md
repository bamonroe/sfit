# SFit

A custom Android app for my self-hosted **SparkyFitness** instance. Native
Kotlin + Jetpack Compose (Material 3, Material You dynamic colour). Built for a
Pixel 8a.

## Status
v0.1 — main screen shows the day's **remaining calories**
(goal − Σ(entry.calories × quantity / serving_size), from `GET /daily-summary`).
Server URL + API key are entered in Settings (DataStore-persisted).

## Build
System JDK is too new for Gradle, so builds use a local JDK 21 pinned in
`~/.gradle/gradle.properties` (`org.gradle.java.home`). Android SDK at
`~/Android/Sdk` (see `local.properties`).

```sh
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
```

## Connectivity
The SparkyFitness base URL is user-configurable (no default). Use the tailnet
address (e.g. `http://fit.bam/api`) — the manifest allows cleartext for that.
The public URL bot-blocks scripted calls, so prefer the tailnet.
