# Android Studio verification checklist

This project is structured for Android Studio with AGP 8.7.3 / Gradle 8.11.1 / Kotlin 2.0.21 / JDK 17.

## Required checks before release
1. Open the `PhysicalActivityGate` directory in Android Studio.
2. Allow Gradle to download the configured distribution and dependencies.
3. Sync Project with Gradle Files.
4. Run `test` and `lint`.
5. Build `assembleDebug`.
6. Install on a physical Android device.
7. Enable the Accessibility Service manually.
8. Select a test app and configure 10 squats.
9. Verify the protected app returns to the launcher and the gate appears.
10. Complete 10 valid squats and verify the app opens.
11. Verify expiry and re-lock.
12. Repeat in Airplane Mode.
13. Test camera denial, sensor absence, reboot, daily limit, cooldown, PIN and emergency bypass.
14. Verify no camera frames/videos are uploaded or persisted.
15. Run release build/signing and inspect the AAB.

This checklist is intentionally not marked passed by the source-only environment; physical Android validation is required for a production-complete claim.
