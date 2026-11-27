# VoiceWave

Full-screen voice-wave display for an old Android tablet (Fire HD 10, LineageOS / Android 7.1).
Listens to the microphone and draws glowing waves that swell when someone talks. Tap to toggle an oscilloscope view.

## Getting the APK
Every push to `main` builds the app on GitHub Actions and publishes it as a release.
The newest APK is always at:

https://github.com/leehadassin/VoiceWave-VC/releases/latest/download/VoiceWave.apk

## Tuning
In `MainActivity.kt`: raise `FLOOR_DB` if the wave twitches in a quiet room; lower `CEIL_DB` if talking doesn't fill the screen.
