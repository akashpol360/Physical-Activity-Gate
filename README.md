# Physical Activity Gate

Android-only, offline-first digital-wellbeing app. The user explicitly selects installed apps and must complete a physical activity before access is authorized.

## Current implementation
- Kotlin + Jetpack Compose + Material 3
- MVVM-style ViewModel/Repository architecture
- Room persistence for protected apps, rules, sessions, authorization, statistics, achievements, usage and settings
- Explicit protected-app selection with launcher-app discovery
- Per-app exercise rules: squats, push-ups, jumping jacks, plank, steps, sit-ups
- CameraX + on-device ML Kit pose detection for camera exercises
- Exercise state machines with movement thresholds and cooldowns
- Android step-counter verification when supported
- Accessibility Service foreground-app gate (user must enable it in Android Settings)
- Temporary local authorization and re-lock behavior
- Optional require-every-time mode
- Daily unlock limit and cooldown setting
- Emergency bypass modes: off, one-per-day, confirmation
- Local statistics, activity history, achievements and streak-ready daily data
- Optional local usage-statistics model
- Local notification scheduling for authorization expiry
- PIN setting foundation using a SHA-256 hash stored locally
- No network permission and no server/account dependency

## Important Android limitations
Android does not expose a universal unrestricted API to forcibly block arbitrary third-party apps. This project uses a user-enabled Accessibility Service as the access-control mechanism and does not use hidden APIs. Device/OEM behavior must be tested, and Play distribution must comply with Google's Accessibility API policies.

Camera verification is deliberately local. Camera frames are processed during an active session and are not uploaded or persisted by this application.

## Build
Open the project folder in Android Studio with JDK 17 and let Gradle resolve dependencies. Run `:app:assembleDebug`.

The preparation environment does not include an Android SDK/Gradle executable, so a device APK/AAB cannot be compiled inside this workspace. The source project is packaged for Android Studio build/test.

## First test
1. Install the debug build.
2. Open Physical Activity Gate.
3. Select an installed app under Protected Apps.
4. Open Rule and choose an exercise/quantity/authorization duration.
5. Enable the Accessibility Service in Android Settings.
6. Open the protected app.
7. Complete the activity locally.
8. Continue to the protected app.
9. Test authorization expiry, require-every-time, daily limit, cooldown, bypass, denied permissions and airplane mode.

## Privacy
Core operation is offline. No login, Firebase, REST API, cloud database, or internet permission is required.
