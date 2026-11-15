#!/usr/bin/env bash
# Prepares a cloud session to build the app: JDK 17 and the Android SDK
# pieces the build uses, installed under $HOME. Safe to re-run: anything
# already installed is skipped.
#
# Downloads come from dl.google.com (SDK) and api.adoptium.net (JDK). When a
# download fails, the same pieces are copied out of the Docker image
# cimg/android instead (docker create, docker cp, docker rm).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ANDROID_SDK_DIR="${ANDROID_SDK_DIR:-$HOME/android-sdk}"
JDK_PARENT_DIR="${JDK_PARENT_DIR:-$HOME/jdk}"
JDK_DIR="$JDK_PARENT_DIR/java-17-openjdk-amd64"

# Keep in step with app/build.gradle.kts.
SDK_PLATFORM="android-36"
SDK_BUILD_TOOLS="35.0.1"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-13114758_latest.zip"
FALLBACK_IMAGE="cimg/android:2025.10"
FALLBACK_SDK_DIR="/home/circleci/android-sdk"
FALLBACK_JDK_DIR="/usr/lib/jvm/java-17-openjdk-amd64"
FALLBACK_JDK_CONF_DIR="/etc/java-17-openjdk"

log() { printf '[cloud-setup] %s\n' "$*"; }

fallback_container=""
cleanup() {
    if [[ -n "$fallback_container" ]]; then
        docker rm "$fallback_container" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

# Creates (once) a stopped container of the fallback image to copy from.
ensure_fallback_container() {
    if [[ -n "$fallback_container" ]]; then
        return 0
    fi
    if ! command -v docker >/dev/null; then
        log "docker is not installed, so there is no fallback"
        exit 1
    fi
    if ! docker image inspect "$FALLBACK_IMAGE" >/dev/null 2>&1; then
        log "pulling $FALLBACK_IMAGE"
        docker pull "$FALLBACK_IMAGE" >/dev/null
    fi
    fallback_container="$(docker create "$FALLBACK_IMAGE")"
}

# copy_from_image <dir in image> <destination dir>: copies the directory's contents.
copy_from_image() {
    local source_path="$1" destination="$2"
    ensure_fallback_container
    mkdir -p "$destination"
    docker cp "$fallback_container:$source_path/." "$destination"
}

# Links into the image's /etc that a copied Debian JDK leaves dangling.
dangling_jdk_config_links() {
    find "$JDK_DIR" -xtype l -lname "$FALLBACK_JDK_CONF_DIR/*"
}

# `java -version` runs even without the security config, so also require
# that every config file is there.
jdk_works() {
    [[ -x "$JDK_DIR/bin/java" ]] &&
        "$JDK_DIR/bin/java" -version >/dev/null 2>&1 &&
        [[ -z "$(dangling_jdk_config_links)" ]]
}

# Debian's JDK keeps its config under /etc and symlinks to it. A copied JDK
# has those links dangling, so replace each one with the file it points to.
repair_jdk_config_links() {
    local dangling
    dangling="$(dangling_jdk_config_links)"
    if [[ -z "$dangling" ]]; then
        return 0
    fi
    log "replacing dangling JDK config links with files from $FALLBACK_IMAGE"
    local conf_copy
    conf_copy="$(mktemp -d)"
    copy_from_image "$FALLBACK_JDK_CONF_DIR" "$conf_copy"
    local link target
    while IFS= read -r link; do
        target="$(readlink "$link")"
        rm "$link"
        cp "$conf_copy/${target#"$FALLBACK_JDK_CONF_DIR"/}" "$link"
    done <<<"$dangling"
    rm -rf "$conf_copy"
}

download_jdk() {
    local archive_dir
    archive_dir="$(mktemp -d)"
    curl -fsSL --retry 2 -o "$archive_dir/jdk.tar.gz" \
        "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" || {
        rm -rf "$archive_dir"
        return 1
    }
    mkdir -p "$JDK_DIR"
    tar -xzf "$archive_dir/jdk.tar.gz" -C "$JDK_DIR" --strip-components=1
    rm -rf "$archive_dir"
}

install_jdk() {
    if jdk_works; then
        log "JDK 17 present at $JDK_DIR"
        return 0
    fi
    if [[ -d "$JDK_DIR" ]]; then
        repair_jdk_config_links
    else
        log "downloading JDK 17"
        if ! download_jdk; then
            log "JDK download failed; copying JDK 17 from $FALLBACK_IMAGE"
            rm -rf "$JDK_DIR"
            copy_from_image "$FALLBACK_JDK_DIR" "$JDK_DIR"
            repair_jdk_config_links
        fi
    fi
    jdk_works || { log "JDK 17 at $JDK_DIR does not run"; exit 1; }
    log "JDK 17 installed at $JDK_DIR"
}

# sdk_package_dir <sdkmanager package>: where that package lives in the SDK.
sdk_package_dir() {
    printf '%s/%s\n' "$ANDROID_SDK_DIR" "${1//;//}"
}

download_sdk_packages() {
    local packages=("$@")
    local sdkmanager="$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
    if [[ ! -x "$sdkmanager" ]]; then
        local archive_dir
        archive_dir="$(mktemp -d)"
        curl -fsSL --retry 2 -o "$archive_dir/cmdline-tools.zip" \
            "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" || {
            rm -rf "$archive_dir"
            return 1
        }
        unzip -q "$archive_dir/cmdline-tools.zip" -d "$archive_dir"
        mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
        rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest"
        mv "$archive_dir/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
        rm -rf "$archive_dir"
    fi
    export JAVA_HOME="$JDK_DIR"
    yes | "$sdkmanager" --sdk_root="$ANDROID_SDK_DIR" --licenses >/dev/null || true
    if [[ ${#packages[@]} -gt 0 ]]; then
        "$sdkmanager" --sdk_root="$ANDROID_SDK_DIR" "${packages[@]}"
    fi
}

copy_sdk_packages_from_image() {
    local package
    for package in "$@"; do
        log "copying $package from $FALLBACK_IMAGE"
        local relative="${package//;//}"
        copy_from_image "$FALLBACK_SDK_DIR/$relative" "$ANDROID_SDK_DIR/$relative"
    done
    copy_from_image "$FALLBACK_SDK_DIR/licenses" "$ANDROID_SDK_DIR/licenses"
}

install_android_sdk() {
    local wanted=("platforms;$SDK_PLATFORM" "build-tools;$SDK_BUILD_TOOLS" "platform-tools" "cmdline-tools;latest")
    local missing=() package
    for package in "${wanted[@]}"; do
        if [[ -d "$(sdk_package_dir "$package")" ]]; then
            log "$package present"
        else
            missing+=("$package")
        fi
    done
    [[ -d "$ANDROID_SDK_DIR/licenses" ]] || missing+=("licenses")
    if [[ ${#missing[@]} -eq 0 ]]; then
        return 0
    fi

    local sdk_packages=()
    for package in "${missing[@]}"; do
        if [[ "$package" != "licenses" ]]; then
            sdk_packages+=("$package")
        fi
    done
    log "installing: ${missing[*]}"
    if ! download_sdk_packages "${sdk_packages[@]}"; then
        log "SDK download failed; copying from $FALLBACK_IMAGE"
        copy_sdk_packages_from_image "${sdk_packages[@]}"
    fi
}

write_local_properties() {
    local properties="$REPO_ROOT/local.properties"
    local line="sdk.dir=$ANDROID_SDK_DIR"
    if [[ -f "$properties" ]] && grep -qxF "$line" "$properties"; then
        log "local.properties already points at $ANDROID_SDK_DIR"
        return 0
    fi
    printf '%s\n' "$line" >"$properties"
    log "wrote $properties"
}

install_jdk
install_android_sdk
write_local_properties

log "done. Build with: JAVA_HOME=$JDK_DIR ./gradlew testDebugUnitTest"
