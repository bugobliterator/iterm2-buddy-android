#!/usr/bin/env bash
# Prepares a cloud session to build the app: JDK 17 and the Android SDK
# packages the build uses, installed under $HOME, plus local.properties.
# Safe to re-run: anything already installed is skipped, and a broken JDK
# is repaired or replaced.
#
# Downloads are pinned and checksummed: the JDK comes from Adoptium
# (Temurin), the SDK from dl.google.com. If a download fails, the JDK,
# platform-tools, cmdline-tools and licenses can be copied out of the pinned
# Docker image below instead. That image predates the platform and
# build-tools this build needs, so without dl.google.com those two fail
# with a message naming the missing package.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ANDROID_SDK_DIR="${ANDROID_SDK_DIR:-$HOME/android-sdk}"
JDK_PARENT_DIR="${JDK_PARENT_DIR:-$HOME/jdk}"
JDK_DIR="$JDK_PARENT_DIR/java-17-openjdk-amd64"

# Keep in step with app/build.gradle.kts.
SDK_PLATFORM="android-37.0"
SDK_BUILD_TOOLS="37.0.0"

# Temurin 17.0.21+9; the checksum is the one Adoptium publishes for it.
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz"
JDK_SHA256="8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711"

# cmdline-tools 23.0. repository2-3.xml lists its sha1 as
# e025545c62a8e64c7559119566a569fb1dec5f60; this is the sha256 of that file.
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
CMDLINE_TOOLS_SHA256="0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8"

# cimg/android:2025.10, pinned by digest.
FALLBACK_IMAGE="cimg/android@sha256:dc1cda740cb5712732734ca8ee61fa8f35d2a0fa55fbc468ad13a52efbdf65ac"
FALLBACK_SDK_DIR="/home/circleci/android-sdk"
FALLBACK_JDK_DIR="/usr/lib/jvm/java-17-openjdk-amd64"
FALLBACK_JDK_CONF_DIR="/etc/java-17-openjdk"
FALLBACK_CACERTS="/etc/ssl/certs/java/cacerts"

log() { printf '[cloud-setup] %s\n' "$*"; }
fail() {
    log "error: $*"
    exit 1
}

temp_dirs=()
fallback_container=""
cleanup() {
    if [[ -n "$fallback_container" ]]; then
        docker rm "$fallback_container" >/dev/null 2>&1 || true
    fi
    if [[ ${#temp_dirs[@]} -gt 0 ]]; then
        rm -rf "${temp_dirs[@]}"
    fi
}
trap cleanup EXIT

# new_temp_dir <variable>: makes a temp dir, removed on exit, and stores its path.
new_temp_dir() {
    local dir
    dir="$(mktemp -d)"
    temp_dirs+=("$dir")
    printf -v "$1" '%s' "$dir"
}

# download_verified <url> <sha256> <destination file>
download_verified() {
    local url="$1" sha256="$2" destination="$3"
    curl -fsSL --retry 2 -o "$destination" "$url" || return 1
    if ! printf '%s  %s\n' "$sha256" "$destination" | sha256sum --check --status; then
        log "checksum mismatch for $url"
        return 1
    fi
}

# Creates (once) a stopped container of the fallback image to copy from.
ensure_fallback_container() {
    if [[ -n "$fallback_container" ]]; then
        return 0
    fi
    command -v docker >/dev/null || fail "download failed and docker is not installed, so there is no fallback"
    if ! docker image inspect "$FALLBACK_IMAGE" >/dev/null 2>&1; then
        log "pulling $FALLBACK_IMAGE"
        docker pull "$FALLBACK_IMAGE" >/dev/null
    fi
    fallback_container="$(docker create "$FALLBACK_IMAGE")"
}

image_has_path() {
    ensure_fallback_container
    docker cp "$fallback_container:$1" - >/dev/null 2>&1
}

# copy_from_image <path in image> <destination>: a directory's contents, or one file.
copy_from_image() {
    ensure_fallback_container
    docker cp "$fallback_container:$1" "$2"
}

# --- JDK -------------------------------------------------------------------

# Links a copied Debian JDK leaves dangling: its config lives in the image's
# /etc, and lib/security/cacerts points at the image's CA store.
dangling_jdk_links() {
    find "$JDK_DIR" -xtype l \( -lname "$FALLBACK_JDK_CONF_DIR/*" -o -path "$JDK_DIR/lib/security/cacerts" \)
}

# `java -version` runs even without the security config, so also require
# that every config file is there.
jdk_works() {
    [[ -x "$JDK_DIR/bin/java" ]] &&
        "$JDK_DIR/bin/java" -version >/dev/null 2>&1 &&
        [[ -z "$(dangling_jdk_links)" ]]
}

# Replaces each dangling link with the file it points to, from the image.
repair_jdk_links() {
    local dangling
    dangling="$(dangling_jdk_links)"
    if [[ -z "$dangling" ]]; then
        return 0
    fi
    log "replacing dangling JDK links with files from the fallback image"
    local conf_copy link target
    new_temp_dir conf_copy
    copy_from_image "$FALLBACK_JDK_CONF_DIR/." "$conf_copy"
    while IFS= read -r link; do
        target="$(readlink "$link")"
        rm "$link"
        if [[ "$target" == "$FALLBACK_JDK_CONF_DIR"/* ]]; then
            cp "$conf_copy/${target#"$FALLBACK_JDK_CONF_DIR"/}" "$link"
        else
            copy_from_image "$FALLBACK_CACERTS" "$link"
        fi
    done <<<"$dangling"
}

# Downloads into a temp dir and moves the JDK into place only once it runs.
# Called under `if !`, where set -e is off, so every step checks itself.
download_jdk() {
    local work
    new_temp_dir work
    download_verified "$JDK_URL" "$JDK_SHA256" "$work/jdk.tar.gz" || return 1
    mkdir "$work/jdk" || return 1
    tar -xzf "$work/jdk.tar.gz" -C "$work/jdk" --strip-components=1 || return 1
    "$work/jdk/bin/java" -version >/dev/null 2>&1 || return 1
    mkdir -p "$JDK_PARENT_DIR" || return 1
    rm -rf "$JDK_DIR" || return 1
    mv "$work/jdk" "$JDK_DIR" || return 1
}

copy_jdk_from_image() {
    local work
    new_temp_dir work
    copy_from_image "$FALLBACK_JDK_DIR/." "$work/jdk"
    mkdir -p "$JDK_PARENT_DIR"
    rm -rf "$JDK_DIR"
    mv "$work/jdk" "$JDK_DIR"
    repair_jdk_links
}

install_jdk() {
    if jdk_works; then
        log "JDK 17 present at $JDK_DIR"
        return 0
    fi
    if [[ -d "$JDK_DIR" && -n "$(dangling_jdk_links)" ]]; then
        repair_jdk_links
    fi
    if ! jdk_works; then
        log "downloading JDK 17"
        if ! download_jdk; then
            log "JDK download failed; copying JDK 17 from the fallback image"
            copy_jdk_from_image
        fi
    fi
    jdk_works || fail "JDK 17 at $JDK_DIR does not run"
    log "JDK 17 installed at $JDK_DIR"
}

# --- Android SDK -------------------------------------------------------------

# sdk_package_dir <sdkmanager package>: where that package lives in the SDK.
sdk_package_dir() {
    printf '%s/%s\n' "$ANDROID_SDK_DIR" "${1//;//}"
}

# A package is installed once its source.properties is there, not merely its dir.
sdk_package_present() {
    [[ -f "$(sdk_package_dir "$1")/source.properties" ]]
}

# Called under `if !`, where set -e is off, so every step checks itself.
install_cmdline_tools() {
    local work
    new_temp_dir work
    download_verified "$CMDLINE_TOOLS_URL" "$CMDLINE_TOOLS_SHA256" "$work/cmdline-tools.zip" || return 1
    unzip -q "$work/cmdline-tools.zip" -d "$work" || return 1
    mkdir -p "$ANDROID_SDK_DIR/cmdline-tools" || return 1
    rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest" || return 1
    mv "$work/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest" || return 1
}

# Called under `if !`, where set -e is off, so every step checks itself.
download_sdk_packages() {
    local sdkmanager="$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
    if [[ ! -x "$sdkmanager" ]]; then
        install_cmdline_tools || return 1
    fi
    local packages=() package
    for package in "$@"; do
        if ! sdk_package_present "$package"; then
            packages+=("$package")
        fi
    done
    export JAVA_HOME="$JDK_DIR"
    yes | "$sdkmanager" --sdk_root="$ANDROID_SDK_DIR" --licenses >/dev/null || true
    if [[ ${#packages[@]} -gt 0 ]]; then
        "$sdkmanager" --sdk_root="$ANDROID_SDK_DIR" "${packages[@]}" || return 1
    fi
}

# Copies what is still missing, after a failed download, from the image.
copy_sdk_packages_from_image() {
    local package relative work
    for package in "$@"; do
        if sdk_package_present "$package"; then
            continue
        fi
        relative="${package//;//}"
        image_has_path "$FALLBACK_SDK_DIR/$relative/source.properties" ||
            fail "$package is not in the fallback image; reach dl.google.com or pick an image that has it"
        log "copying $package from the fallback image"
        new_temp_dir work
        copy_from_image "$FALLBACK_SDK_DIR/$relative/." "$work/package"
        mkdir -p "$(dirname "$(sdk_package_dir "$package")")"
        rm -rf "$(sdk_package_dir "$package")"
        mv "$work/package" "$(sdk_package_dir "$package")"
    done
    if [[ ! -d "$ANDROID_SDK_DIR/licenses" ]]; then
        copy_from_image "$FALLBACK_SDK_DIR/licenses/." "$ANDROID_SDK_DIR/licenses"
    fi
}

install_android_sdk() {
    local wanted=("platforms;$SDK_PLATFORM" "build-tools;$SDK_BUILD_TOOLS" "platform-tools" "cmdline-tools;latest")
    local missing=() package
    for package in "${wanted[@]}"; do
        if sdk_package_present "$package"; then
            log "$package present"
        else
            missing+=("$package")
        fi
    done
    if [[ ${#missing[@]} -eq 0 && -d "$ANDROID_SDK_DIR/licenses" ]]; then
        return 0
    fi
    log "installing: ${missing[*]:-licenses}"
    if ! download_sdk_packages "${missing[@]}"; then
        log "SDK download failed; copying from the fallback image"
        copy_sdk_packages_from_image "${missing[@]}"
    fi
}

# --- local.properties ----------------------------------------------------------

# Sets sdk.dir and keeps every other key.
write_local_properties() {
    local properties="$REPO_ROOT/local.properties"
    local line="sdk.dir=$ANDROID_SDK_DIR"
    if [[ -f "$properties" ]] && grep -qxF "$line" "$properties"; then
        log "local.properties already points at $ANDROID_SDK_DIR"
        return 0
    fi
    if [[ -f "$properties" ]] && grep -q '^sdk\.dir=' "$properties"; then
        local updated
        new_temp_dir updated
        awk -v line="$line" '/^sdk\.dir=/ { print line; next } { print }' "$properties" >"$updated/local.properties"
        cat "$updated/local.properties" >"$properties"
    else
        printf '%s\n' "$line" >>"$properties"
    fi
    log "set $line in $properties"
}

install_jdk
install_android_sdk
write_local_properties

log "done. Build with: JAVA_HOME=$JDK_DIR ./gradlew testDebugUnitTest"
