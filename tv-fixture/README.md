# Test-only TV fixture

Separate APK, never included in the real app or its distributable artifact. Displays synthetic TV-like UI, **not a broadcast**. No networking or permissions. Build with `./gradlew :tv-fixture:assembleDebug`, explicitly install only on a test device.

Calibration: package `dev.nicotv.fixture`, station ID `dev.nicotv.fixture:id/channel_label`, live marker `dev.nicotv.fixture:id/live_indicator`.

Launch: `adb shell am start -n dev.nicotv.fixture/.FixtureActivity --es station jk4`.
Switch with ch buttons or `--es station jk6`. Guide: `--es screen guide`; the live marker and station disappear. Use Home to test foreground departure. Passing this fixture is not testing an OEM tuner/hardware video plane.
