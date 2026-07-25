#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=pins.sh
source "$script_dir/pins.sh"

output_root="${1:-"$script_dir/build"}"
download_root="$output_root/downloads"
published_root="$output_root/bin"
published_license_root="$output_root/licenses"

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        printf 'Required command is unavailable: %s\n' "$1" >&2
        exit 1
    fi
}

sha256_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

require_hash() {
    local file="$1"
    local expected="$2"
    local actual
    actual="$(sha256_file "$file")"
    if [[ "$actual" != "$expected" ]]; then
        printf 'SHA-256 mismatch for %s\nexpected: %s\nactual:   %s\n' \
            "$file" "$expected" "$actual" >&2
        exit 1
    fi
}

canonical_tree_sha256() {
    local root="$1"
    (
        cd "$root"
        find . -type f -print |
            LC_ALL=C sort |
            while IFS= read -r relative_file; do
                local file_size
                local file_hash
                file_size="$(wc -c < "$relative_file" | tr -d '[:space:]')"
                file_hash="$(sha256_file "$relative_file")"
                printf '%s\t%s\t%s\n' \
                    "${relative_file#./}" "$file_size" "$file_hash"
            done
    ) | if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 | awk '{print $1}'
    else
        sha256sum | awk '{print $1}'
    fi
}

require_tree_hash() {
    local root="$1"
    local expected="$2"
    local actual
    actual="$(canonical_tree_sha256 "$root")"
    if [[ "$actual" != "$expected" ]]; then
        printf 'Canonical source tree changed for %s\nexpected: %s\nactual:   %s\n' \
            "$root" "$expected" "$actual" >&2
        exit 1
    fi
}

download_archive() {
    local url="$1"
    local destination="$2"
    if [[ -f "$destination" ]]; then
        return
    fi
    local temporary="$destination.part"
    rm -f -- "$temporary"
    curl --fail --location --silent --show-error "$url" --output "$temporary"
    mv -- "$temporary" "$destination"
}

for command_name in curl tar find sort javac jar awk cmp wc tr; do
    require_command "$command_name"
done

java_version="$(javac -version 2>&1)"
if [[ "$java_version" != javac\ 17.* ]]; then
    printf 'JDK 17 is required; found %s\n' "$java_version" >&2
    exit 1
fi

mkdir -p -- "$download_root" "$published_root" "$published_license_root"

jobb_archive="$download_root/jobb-$JOBB_COMMIT.tar.gz"
fat32lib_archive="$download_root/fat32lib-$FAT32LIB_COMMIT.tar.gz"

download_archive \
    "https://android.googlesource.com/platform/tools/base/+archive/$JOBB_COMMIT/jobb.tar.gz" \
    "$jobb_archive"
download_archive \
    "https://android.googlesource.com/platform/tools/external/fat32lib/+archive/$FAT32LIB_COMMIT.tar.gz" \
    "$fat32lib_archive"

work_root="$(mktemp -d "$output_root/.job-build.XXXXXX")"
trap 'rm -rf -- "$work_root"' EXIT

jobb_source="$work_root/jobb"
fat32lib_source="$work_root/fat32lib"
classes_root="$work_root/classes"
mkdir -p -- "$jobb_source" "$fat32lib_source" "$classes_root"
tar -xzf "$jobb_archive" -C "$jobb_source"
tar -xzf "$fat32lib_archive" -C "$fat32lib_source"

require_tree_hash "$jobb_source" "$JOBB_TREE_SHA256"
require_tree_hash "$fat32lib_source" "$FAT32LIB_TREE_SHA256"
require_hash "$jobb_source/NOTICE" "$JOBB_NOTICE_SHA256"
require_hash "$fat32lib_source/NOTICE" "$FAT32LIB_NOTICE_SHA256"
require_hash "$script_dir/licenses/APACHE-2.0.txt" "$APACHE_LICENSE_SHA256"
cmp "$jobb_source/NOTICE" "$script_dir/licenses/JOBB-NOTICE.txt"
cmp "$fat32lib_source/NOTICE" "$script_dir/licenses/FAT32LIB-LGPL-2.1.txt"

source_list="$work_root/sources.txt"
find "$jobb_source/src/main/java" "$fat32lib_source/src/main/java" \
    -type f -name '*.java' -print |
    LC_ALL=C sort > "$source_list"

observed_source_count="$(wc -l < "$source_list" | tr -d '[:space:]')"
if [[ "$observed_source_count" != "$JOBB_JAVA_SOURCE_COUNT" ]]; then
    printf 'Unexpected Java source count: expected %s, found %s\n' \
        "$JOBB_JAVA_SOURCE_COUNT" "$observed_source_count" >&2
    exit 1
fi

source_files=()
while IFS= read -r source_file; do
    source_files+=("$source_file")
done < "$source_list"

javac \
    --release 8 \
    -encoding UTF-8 \
    -g:none \
    -d "$classes_root" \
    "${source_files[@]}"

manifest="$work_root/MANIFEST.MF"
printf 'Manifest-Version: 1.0\r\nMain-Class: com.android.jobb.Main\r\n\r\n' > "$manifest"

unverified_jar="$work_root/jobb.jar"
jar \
    --create \
    --file "$unverified_jar" \
    --manifest "$manifest" \
    --date="$JOBB_JAR_TIMESTAMP" \
    -C "$classes_root" .

observed_jar_sha256="$(sha256_file "$unverified_jar")"
if [[ "$JOBB_JAR_SHA256" != TO_BE_REPLACED_* && \
      "$observed_jar_sha256" != "$JOBB_JAR_SHA256" ]]; then
    printf 'Reproducible JOBB JAR changed\nexpected: %s\nactual:   %s\n' \
        "$JOBB_JAR_SHA256" "$observed_jar_sha256" >&2
    exit 1
fi

cp -- "$jobb_source/NOTICE" "$published_license_root/JOBB-NOTICE.txt"
cp -- "$script_dir/licenses/APACHE-2.0.txt" "$published_license_root/APACHE-2.0.txt"
cp -- "$fat32lib_source/NOTICE" "$published_license_root/FAT32LIB-LGPL-2.1.txt"
mv -- "$unverified_jar" "$published_root/jobb.jar"

printf 'JOBB JAR: %s\n' "$published_root/jobb.jar"
printf 'JOBB SHA-256: %s\n' "$observed_jar_sha256"
printf 'Pinned JOBB commit: %s\n' "$JOBB_COMMIT"
printf 'Pinned fat32lib commit: %s\n' "$FAT32LIB_COMMIT"
