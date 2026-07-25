# Pinned AOSP JOBB host tool

This directory builds the host-side `jobb.jar` used by the Android Runtime
Image feasibility work. It does not contain vendored AOSP source code or a
prebuilt JAR.

## Requirements

- JDK 17
- `bash`, `curl`, `tar`, and standard POSIX command-line tools
- network access to `android.googlesource.com` on the first build

The Android SDK and NDK are not required for this host tool.

## Build

```bash
./tooling/runtime-image/jobb/build-jobb.sh
```

Outputs are written under the ignored `tooling/runtime-image/jobb/build/`
directory:

```text
build/
├── bin/jobb.jar
├── downloads/
└── licenses/
```

The build fails closed if either canonical unpacked source tree, either upstream
NOTICE, the Java source count, or the reproducible JAR hash changes. Gitiles
generates archive compression dynamically, so the build deliberately binds the
canonical unpacked file tree rather than the unstable `.tar.gz` byte stream.

## Verify from an empty cache

```bash
./tooling/runtime-image/jobb/verify-jobb.sh
```

Verification rebuilds the JAR in a fresh temporary directory and performs a
64 MiB OBB creation smoke test. It checks the package metadata and OBB footer
magic; filesystem mounting remains an Android device test.

## Source and license boundary

Exact AOSP commits and canonical source identities are in `pins.sh`. Original
upstream license texts and provenance are centralized in `licenses/`. The build
copies the verified texts beside its output JAR.

This tooling is not an Android runtime backend, does not change slot admission,
and does not modify STM Core Plan v2.
