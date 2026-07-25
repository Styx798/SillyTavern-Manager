# AOSP JOBB build-tool sources

The source code is not vendored in the STM repository. The build downloads
fixed source archives from Android Open Source Project Gitiles and verifies a
canonical manifest of every unpacked file before compilation. Gitiles may
produce different gzip bytes for the same commit, so compressed archive hashes
are not used as source identities.

## JOBB

- Project: Android Open Source Project `platform/tools/base`, subdirectory `jobb`
- Commit: `01c102600be4d4de633751d6ca0bc09eda527a92`
- Archive:
  `https://android.googlesource.com/platform/tools/base/+archive/01c102600be4d4de633751d6ca0bc09eda527a92/jobb.tar.gz`
- Canonical unpacked tree SHA-256:
  `49ecb7cb6f6b2658586344e495e10ca243c9507f1a9f02ec65f7be860f064893`
- Upstream NOTICE SHA-256:
  `c5f2c2bdc559f005a6c6561d322690c355c28b756ce974108df9967905f8533b`
- License file in this directory: `JOBB-NOTICE.txt`
- Full Apache License 2.0 text: `APACHE-2.0.txt`
- Apache License text SHA-256:
  `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`

The upstream NOTICE contains the applicable Apache-2.0, Bouncy Castle,
Cryptix, and Twofish notices for the JOBB sources. The complete Apache-2.0
text is copied from the canonical license published by the Apache Software
Foundation because the upstream NOTICE contains only the standard license
header and URL.

## fat32lib

- Project: Android Open Source Project `platform/tools/external/fat32lib`
- Commit: `11b1061d834666a605e7a02fe757341ef224a04d`
- Archive:
  `https://android.googlesource.com/platform/tools/external/fat32lib/+archive/11b1061d834666a605e7a02fe757341ef224a04d.tar.gz`
- Canonical unpacked tree SHA-256:
  `0cae91cda7f0453780527df477b97fc865d5c27b2f08320cffdbc789094fb6c1`
- Upstream NOTICE SHA-256:
  `363d08b1a2e8504b9b33f661b7e76802905bc8a82b86e945157c1249f7d646e4`
- License file in this directory: `FAT32LIB-LGPL-2.1.txt`

The upstream repository marks the module as LGPL and supplies the GNU Lesser
General Public License version 2.1 in its NOTICE.

These notices describe third-party build tooling only. They do not replace the
licenses required for SillyTavern runtime packages.
