# KX IRC (Android)

A minimal Android IRC client with ZNC-friendly connection parameters, channel switching, and basic IRC color parsing.

## Prereqs

Install Java 17 and Android SDK command-line tools:

```
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk unzip curl
```

Install Android SDK command-line tools:

```
mkdir -p "$HOME/Android/Sdk/cmdline-tools"
cd "$HOME/Android/Sdk/cmdline-tools"
curl -L -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip cmdline-tools.zip
mv cmdline-tools latest
```

Add SDK paths to your shell (put in `~/.bashrc` or `~/.zshrc`):

```
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

Install SDK components and accept licenses:

```
sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## Build

```
./gradlew assembleDebug
```

APK output:
- `app/build/outputs/apk/debug/app-debug.apk`

## Test

Unit tests:
```
./gradlew test
```

UI tests (requires emulator or device):
```
./gradlew connectedAndroidTest
```

## Run in an emulator

Create and start an emulator:

```
sdkmanager --install "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n kxirc -k "system-images;android-34;google_apis;x86_64"
$ANDROID_SDK_ROOT/emulator/emulator -avd kxirc
```

Install the debug APK:

```
./gradlew installDebug
```

## Run on a device

1) Enable **Developer Options** and **USB debugging** on your device.
2) Plug in the device and verify it shows up:

```
adb devices
```

3) Install the debug APK:

```
./gradlew installDebug
```
