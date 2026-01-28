# Cosmic Konnect for Android

Android companion app for [Cosmic Konnect](https://github.com/reality2-roycdavies/cosmic-konnect), enabling seamless communication between your Android device and Linux desktop running the COSMIC desktop environment.

**Desktop app:** [cosmic-konnect](https://github.com/reality2-roycdavies/cosmic-konnect)

## Features

- **Device Discovery** - Automatically discover your desktop on the local network
- **Clipboard Sync** - Share clipboard content between phone and desktop
- **Ping/Find Device** - Send pings to your desktop, receive "find my phone" requests
- **Haptic Feedback** - Vibrates when clipboard is received from desktop
- **Background Service** - Runs as a foreground service for reliable connectivity
- **Autostart** - Automatically starts when your phone boots

### Coming Soon

- BLE (Bluetooth Low Energy) discovery
- Wi-Fi Direct support
- File transfer
- Notification mirroring

## Installation

### From APK (Recommended)

1. Download the latest APK from [Releases](https://github.com/reality2-roycdavies/cosmic-konnect-android/releases)
2. Enable "Install from unknown sources" if prompted
3. Install the APK

### Building from Source

**Requirements:**
- Android Studio Arctic Fox or later
- JDK 17 or later
- Android SDK 34

```bash
# Clone the repository
git clone https://github.com/reality2-roycdavies/cosmic-konnect-android.git
cd cosmic-konnect-android

# Build debug APK
./gradlew assembleDebug

# APK will be at app/build/outputs/apk/debug/app-debug.apk
```

**Or using Android Studio:**
1. Open the project in Android Studio
2. Click Build > Build Bundle(s) / APK(s) > Build APK(s)
3. APK will be in `app/build/outputs/apk/debug/`

## Usage

### Initial Setup

1. Install and open Cosmic Konnect on your Android device
2. Grant the requested permissions (see Permissions section below)
3. Ensure your desktop is running [cosmic-konnect](https://github.com/reality2-roycdavies/cosmic-konnect) with `--tray`
4. Both devices should appear in each other's device lists

### Connecting to Desktop

1. Your desktop should appear under "Discovered Devices"
2. The status will show "Connected" when successfully paired
3. If not connected, tap the refresh button or pull down to refresh

### Sharing Clipboard

**Phone to Desktop:**
1. Copy any text on your phone
2. Open Cosmic Konnect (or it may already be running)
3. Tap the clipboard icon (📋) in the top bar
4. Your desktop will show a notification with the clipboard content

**Desktop to Phone:**
- Copy text on your desktop
- The clipboard automatically syncs to your phone
- Phone vibrates to confirm receipt

### Ping Desktop

- Tap the bell icon (🔔) next to a connected device
- Your desktop will show a ping notification

## Permissions

The app requests the following permissions:

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Network communication with desktop |
| `ACCESS_NETWORK_STATE` | Check network connectivity |
| `ACCESS_WIFI_STATE` | Wi-Fi network information |
| `FOREGROUND_SERVICE` | Keep service running reliably |
| `POST_NOTIFICATIONS` | Show connection status notification |
| `VIBRATE` | Haptic feedback on clipboard received |
| `RECEIVE_BOOT_COMPLETED` | Autostart on device boot |
| `BLUETOOTH_*` | Future BLE discovery support |
| `ACCESS_FINE_LOCATION` | Required for Wi-Fi/BLE scanning |
| `NEARBY_WIFI_DEVICES` | Future Wi-Fi Direct support |

## Network Requirements

Both devices must be on the same local network. The app uses:

| Port | Protocol | Purpose |
|------|----------|---------|
| 17160 | UDP | Device discovery broadcasts |
| 17161 | TCP | Encrypted connections |

## Troubleshooting

### Desktop not appearing in device list

1. Ensure both devices are on the same Wi-Fi network
2. Check that the desktop app is running (`cosmic-konnect --tray`)
3. Pull down to refresh or tap the refresh button
4. Check your router doesn't block local device communication

### Connection keeps dropping

1. Disable battery optimization for Cosmic Konnect:
   - Settings > Apps > Cosmic Konnect > Battery > Unrestricted
2. Ensure the app isn't being killed by aggressive battery saving
3. Lock the app in recent apps (varies by manufacturer)

### Clipboard not syncing

1. Ensure the device shows "Connected" status
2. On Android 10+, clipboard access requires the app to be in foreground
3. Open the app and tap the clipboard icon to share manually

### Service not starting on boot

1. Ensure the app has autostart permission (Settings > Apps > Cosmic Konnect)
2. Some manufacturers require additional autostart settings
3. Disable battery optimization as described above

## Protocol

Cosmic Konnect uses its own lightweight protocol (CKP - Cosmic Konnect Protocol) with:
- MessagePack encoding for efficiency
- X25519 key exchange for secure pairing
- ChaCha20-Poly1305 encryption for messages

## Building

### Debug Build

```bash
./gradlew assembleDebug
```

### Release Build

```bash
./gradlew assembleRelease
```

Note: Release builds require signing configuration in `app/build.gradle`.

## Project Structure

```
app/src/main/java/io/github/cosmickonnect/
├── ble/                 # BLE discovery (WIP)
├── ckp/                 # Cosmic Konnect Protocol implementation
│   ├── Connection.kt    # TCP connection handling
│   ├── CkpService.kt    # Main CKP service manager
│   ├── Crypto.kt        # Encryption/key exchange
│   ├── Discovery.kt     # UDP discovery
│   ├── Messages.kt      # Message encoding/decoding
│   └── Protocol.kt      # Protocol constants
├── protocol/            # Legacy KDE Connect protocol
├── service/             # Android service components
│   ├── KonnectService.kt
│   └── BootReceiver.kt
├── ui/                  # Compose UI
│   └── MainActivity.kt
└── wifidirect/          # Wi-Fi Direct (WIP)
```

## License

MIT License - see LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

## Related Projects

- [cosmic-konnect](https://github.com/reality2-roycdavies/cosmic-konnect) - Linux desktop app
- [COSMIC Desktop](https://github.com/pop-os/cosmic-epoch) - The COSMIC desktop environment
- [KDE Connect](https://kdeconnect.kde.org/) - Inspiration for this project
