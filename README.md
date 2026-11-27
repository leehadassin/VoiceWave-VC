# VoiceWave

Full-screen voice-wave display for an old Android tablet (Fire HD 10, LineageOS / Android 7.1).
Listens to the microphone and draws glowing waves that swell when someone talks. Tap to toggle an oscilloscope view.

## Getting the APK
Every push builds the app on GitHub Actions. Open the **Actions** tab, pick the latest **Build APK** run,
and download **VoiceWave-apk** from the Artifacts section. Unzip it to get `app-debug.apk`.

## Tuning
In `MainActivity.kt`: raise `FLOOR_DB` if the wave twitches in a quiet room; lower `CEIL_DB` if talking doesn't fill the screen.
