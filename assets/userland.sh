#!/usr/bin/env bash
set -euo pipefail

archive="/output/${1:?missing output filename}"
packages=(
    filesystem
    glibc
    bash
    coreutils
    acl
    attr
    brotli
    gmp
    libcap
    ncurses
    openssl
    procps-ng
    readline
    systemd-libs
    libgcc
    libgcrypt
    libgpg-error
    lz4
    util-linux
    util-linux-libs
    xz
    zlib
    zstd
)

rootfs=$(mktemp -d)
partial="$archive.part"

cleanup() {
    rm -rf "$rootfs" "$partial"
}

trap cleanup EXIT

printf 'Server = https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch\n' \
    > /etc/pacman.d/mirrorlist
printf 'Server = https://mirrors.ustc.edu.cn/cachyos/repo/$arch/$repo\n' \
    > /etc/pacman.d/cachyos-mirrorlist

pacman -Sy --needed --noconfirm --disable-sandbox-network erofs-utils
mkdir -p "$rootfs/var/lib/pacman"
pacman -Sydd --root "$rootfs" --noconfirm --disable-sandbox-network "${packages[@]}"

rm -rf \
    "$rootfs/var/cache"/* \
    "$rootfs/var/db" \
    "$rootfs/var/lib/pacman" \
    "$rootfs/var/log/pacman.log" \
    "$rootfs/usr/include" \
    "$rootfs/usr/lib/"{cmake,pkgconfig} \
    "$rootfs/usr/share/"{aclocal,doc,factory,i18n,info,libalpm,licenses,locale,man,pixmaps,readline}
find "$rootfs" -type f \( \
    -name '*.a' -o -name '*.o' -o -name '*.debug' \
\) -delete
rm -rf "$rootfs/var/log"/* "$rootfs/var/tmp"/*

mkfs.erofs \
    -x-1 \
    --all-root \
    -z zstd,level=22 \
    -C 1048576 \
    -Eall-fragments,dedupe \
    "$partial" "$rootfs/"
mv "$partial" "$archive"
