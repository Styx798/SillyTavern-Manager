STM Core 0.1.0 embeds one Javet Android Node artifact at build time:

  default: com.caoccao.javet:javet-node-android-i18n:5.0.9
  comparison (-PstmJavetI18n=false): com.caoccao.javet:javet-node-android:5.0.9

The build extracts the authoritative license files shipped inside the selected AAR into:

  third_party/<selected-artifact>-5.0.9/LICENSE
  third_party/<selected-artifact>-5.0.9/LICENSE.node
  third_party/<selected-artifact>-5.0.9/LICENSE.v8

Upstream project: https://github.com/caoccao/Javet
Javet license: Apache License 2.0
Embedded Node.js license and third-party notices: LICENSE.node
The i18n artifact's bundled ICU / Unicode licenses and notices are included in LICENSE.node.
Embedded V8 license: LICENSE.v8

Stage 2 safe ZIP inspection uses Apache Commons Compress 1.28.0 and its resolved
runtime dependencies:

  org.apache.commons:commons-compress:1.28.0
  commons-codec:commons-codec:1.19.0
  commons-io:commons-io:2.20.0
  org.apache.commons:commons-lang3:3.18.0

The build extracts each dependency's authoritative Apache license and notice to:

  third_party/<artifact>-<version>/LICENSE.txt
  third_party/<artifact>-<version>/NOTICE.txt

Upstream project: https://commons.apache.org/proper/commons-compress/
License: Apache License 2.0

API 31 Ed25519 signature verification uses the lightweight primitive from:

  org.bouncycastle:bcprov-jdk15to18:1.85.1

STM does not install or select the Bouncy Castle JCA provider globally. The
authoritative MIT license text is included at:

  third_party/bcprov-jdk15to18-1.85.1/LICENSE.txt

Upstream project: https://www.bouncycastle.org/
License: MIT

Stage 3B embeds a fixed npm CLI toolchain as a versioned APK asset:

  npm 11.6.2
  upstream: https://registry.npmjs.org/npm/-/npm-11.6.2.tgz
  registry SRI: sha512-7iKzNfy8lWYs3zq4oFPa8EXZz5xt9gQNKJZau3B1ErLBb6bF7sBJ00x09485DOvRT2l5Gerbl3VlZNT57MxJVA==

The deterministic asset identity and its required entry hashes are recorded at:

  stm_core/tools/npm/11.6.2/npm-tool-manifest.stm

The npm Artistic-2.0 license and path-specific inventories for the bundled package
instances are included at:

  third_party/npm-11.6.2/LICENSE.txt
  third_party/npm-11.6.2/PACKAGE-LICENSES.json
  third_party/npm-11.6.2/SUPPLEMENTAL-LICENSES.json
  third_party/npm-11.6.2/supplemental/

The upstream npm tarball contains nine package instances with a declared SPDX license
but no package-local license text. STM keeps that upstream fact visible in
PACKAGE-LICENSES.json and supplies the missing complete license and attribution texts
separately. SUPPLEMENTAL-LICENSES.json binds every affected archive path to its exact
package version, registry identity, upstream repository revision, and supplemental
asset hash. The resulting npm toolchain license gap count is zero.

SillyTavern Manager is not affiliated with npm, Inc. or GitHub, Inc., and does not use
the npm logo.
