#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
destination=${1:-"$repo_root/iosApp/Resources/Runtime"}
rootfs_url=https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz
rootfs_sha256=f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1

mkdir -p "$destination"

rootfs="$destination/root.tar.gz"
if [ ! -f "$rootfs" ] || [ "$(shasum -a 256 "$rootfs" | awk '{print $1}')" != "$rootfs_sha256" ]; then
    temporary_rootfs=$(mktemp "${TMPDIR:-/tmp}/aether-rootfs.XXXXXX")
    trap 'rm -f "$temporary_rootfs"' EXIT INT TERM
    curl --fail --location --retry 3 --output "$temporary_rootfs" "$rootfs_url"
    actual_sha256=$(shasum -a 256 "$temporary_rootfs" | awk '{print $1}')
    if [ "$actual_sha256" != "$rootfs_sha256" ]; then
        echo "Alpine rootfs checksum mismatch: $actual_sha256" >&2
        exit 1
    fi
    mv "$temporary_rootfs" "$rootfs"
    trap - EXIT INT TERM
fi

android_kimi_tgz="$repo_root/app/src/main/assets/runtimes/kimi/kimi-code-0.38.0.tgz"
if [ -f "$android_kimi_tgz" ]; then
    staging=$(mktemp -d "${TMPDIR:-/tmp}/aether-kimi.XXXXXX")
    trap 'rm -rf "$staging"' EXIT INT TERM
    tar -xzf "$android_kimi_tgz" -C "$staging"
    package_root="$staging"
    if [ -d "$staging/package" ]; then
        package_root="$staging/package"
    fi
    rm -rf "$package_root/dist-web" "$package_root/native"
    mkdir -p "$destination"
    tar -czf "$destination/kimi-code-0.38.0.tgz" -C "$package_root" .
    trap - EXIT INT TERM
    rm -rf "$staging"
fi
