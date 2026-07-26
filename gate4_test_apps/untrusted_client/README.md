# Gate 4 untrusted Core client

This standalone Android test fixture verifies that a different local app and Linux UID cannot bind
the private STM Core service. It is intentionally outside the product Gradle project so normal STM
builds do not assemble or package the fixture.

Build it with the repository wrapper:

```bash
./gradlew -p gate4_test_apps/untrusted_client assembleDebug assembleDebugAndroidTest
```

Install the STM debug APK first, then install both fixture APKs and run:

```bash
adb shell am instrument -w -r \
  io.github.styx798.sillytavernmanager.gate4.untrusted.test/io.github.styx798.sillytavernmanager.gate4.untrusted.UntrustedCoreBindingInstrumentation
```

The test passes only when the fixture and STM have different Linux UIDs and Android rejects the
explicit Core Service binding. A successful bind is always a failure.
