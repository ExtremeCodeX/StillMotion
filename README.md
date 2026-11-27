<h1><img src="assets/themes/stillmotion_icon.png" width="44" height="44" alt="" align="middle"> StillMotion</h1>

**English** · [Português (Brasil)](README.pt-BR.md)

StillMotion is an Android live wallpaper app. The public APK ships with an empty library. It imports wallpaper packs from local storage, previews their lock and home screen artwork, and opens the Android picker when you apply a theme.

Shaders draw at the surface resolution. Video themes use hardware decoding when supported. A pack can animate when the screen wakes or unlocks, with optional motion after unlock. When the wallpaper is hidden or the screen turns off, the app stops drawing and releases playback resources. The app works offline without root or an external wallpaper app.

## Use it

[Download StillMotion 0.7.0 (APK)](StillMotion-0.7.0.apk)

1. Open StillMotion and choose **Import wallpaper pack**. Select a compatible ZIP as-is.
2. Browse the gallery and switch between **Lock screen** and **Home screen** to preview a theme.
3. Choose **Apply wallpaper** and confirm in the Android picker.

Live playback uses destination flags available on Android 14 and newer. Older versions and power-saving mode use a still image. [Create a compatible pack](docs/wallpapers-comunidade.md) (Portuguese).

## Build and tests

The build uses JDK 17, Android SDK 35, Build Tools 37 and NDK r27d. Set `ANDROID_SDK_ROOT` and install Rust targets `aarch64-linux-android` and `x86_64-linux-android`. Host tests also use Python with Pillow.

```sh
scripts/test-host.sh
scripts/build-android.sh debug
```

The signed test APK is generated at `build/debug/StillMotion-debug.apk`.

Host tests cover Rust and Java playback policy, the JNI bridge and malformed packs. An offline Android emulator verified import and preview. A short physical-device test observed low CPU activity after the wallpaper was hidden. That test did not establish effects on gaming FPS or battery life.

## License

The StillMotion source code is licensed under the [GNU General Public License v3.0](LICENSE). Independently created wallpaper packs keep their creators’ chosen licenses.
