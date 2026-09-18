# Phone Worker Android V1.1

This Android project is independent from JARVIS.

## Important
- No relay or device token is hardcoded in source.
- Configure the relay URL and DEVICE_TOKEN inside the app after installation.
- Default local relay: `ws://127.0.0.1:8787/ws/device`
- Default device id: `phone-01`

## Build with GitHub Actions
Push this folder as the root of a private GitHub repository. The included workflow builds a debug APK and uploads it as the artifact `phone-worker-debug-apk`.

## After installing
1. Open Phone Worker.
2. Keep relay URL as `ws://127.0.0.1:8787/ws/device` when relay runs in Termux on the same phone.
3. Set device id to `phone-01`.
4. Paste the DEVICE_TOKEN from the relay `.env`.
5. Save.
6. Open Accessibility and enable Phone Worker.
7. Return to the app and tap Start Worker.
8. Use the local console to confirm the device appears online.
