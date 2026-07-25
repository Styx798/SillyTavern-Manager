#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=pins.sh
source "$script_dir/pins.sh"

if command -v shasum >/dev/null 2>&1; then
    sha256_command=(shasum -a 256)
else
    sha256_command=(sha256sum)
fi

verification_root="$(mktemp -d "${TMPDIR:-/tmp}/stm-jobb-verify.XXXXXX")"
trap 'rm -rf -- "$verification_root"' EXIT

"$script_dir/build-jobb.sh" "$verification_root/build"

jobb_jar="$verification_root/build/bin/jobb.jar"
observed_sha256="$("${sha256_command[@]}" "$jobb_jar" | awk '{print $1}')"
if [[ "$observed_sha256" != "$JOBB_JAR_SHA256" ]]; then
    printf 'JOBB verification hash mismatch\nexpected: %s\nactual:   %s\n' \
        "$JOBB_JAR_SHA256" "$observed_sha256" >&2
    exit 1
fi

fixture_input="$verification_root/input"
fixture_obb="$verification_root/fixture.obb"
fixture_footer="$verification_root/footer.bin"
mkdir -p -- "$fixture_input"
printf 'stm-jobb-verification\n' > "$fixture_input/probe.txt"
dd if=/dev/zero of="$fixture_input/padding.bin" bs=1048576 count=64 2>/dev/null

java -jar "$jobb_jar" \
    -pn io.github.styx798.sillytavernmanager \
    -pv 1 \
    -d "$fixture_input" \
    -o "$fixture_obb"

tail -c 512 "$fixture_obb" > "$fixture_footer"
grep -a -F -q 'io.github.styx798.sillytavernmanager' "$fixture_footer"
observed_footer_magic="$(tail -c 4 "$fixture_obb" | od -An -tx1 | tr -d '[:space:]')"
if [[ "$observed_footer_magic" != "83990501" ]]; then
    printf 'Unexpected OBB footer magic: %s\n' "$observed_footer_magic" >&2
    exit 1
fi

printf 'Verified reproducible JOBB JAR: %s\n' "$observed_sha256"
printf 'Verified OBB creation, package metadata, and footer magic\n'
