#!/usr/bin/env bash
set -euo pipefail

archive="/output/${1:?missing output filename}"
packages=(
    bash
    fastfetch
    pacman
    archlinux-keyring
    cachyos-keyring
    cachyos-mirrorlist
    cachyos-v3-mirrorlist
    fuse2fs
    fuse3
    fuse-overlayfs
    util-linux
    pciutils
    python
    procps-ng
    iputils
    iproute2
)

rootfs=$(mktemp -d)
chmod 0755 "$rootfs"
partial="$archive.part"
keyring="$rootfs/etc/pacman.d/gnupg"

cleanup() {
    rm -rf "$rootfs" "$partial"
}

trap cleanup EXIT

sed -i '/^\[cachyos\]$/i\
[cachyos-v3]\
Include = /etc/pacman.d/cachyos-v3-mirrorlist\
[cachyos-core-v3]\
Include = /etc/pacman.d/cachyos-v3-mirrorlist\
[cachyos-extra-v3]\
Include = /etc/pacman.d/cachyos-v3-mirrorlist\
' /etc/pacman.conf
printf 'Server = https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch\n' \
    > /etc/pacman.d/mirrorlist
printf 'Server = https://mirrors.ustc.edu.cn/cachyos/repo/$arch/$repo\n' \
    > /etc/pacman.d/cachyos-mirrorlist
printf 'Server = https://mirrors.ustc.edu.cn/cachyos/repo/$arch_v3/$repo\n' \
    > /etc/pacman.d/cachyos-v3-mirrorlist

pacman -Sy --needed --noconfirm --disable-sandbox-network erofs-utils
mkdir -p "$rootfs/var/lib/pacman"
install -Dm644 /etc/os-release "$rootfs/etc/os-release"
pacman -Sy \
    --root "$rootfs" \
    --noconfirm \
    --disable-sandbox-network \
    "${packages[@]}"

sed -i 's/^root:[^:]*:/root::/' "$rootfs/etc/shadow"
grep -q '^root::' "$rootfs/etc/shadow"

install -d -m 0700 "$keyring"
install -Dm755 /usr/local/share/cpos/init "$rootfs/init"
pacman-key --gpgdir "$keyring" --init
pacman-key --gpgdir "$keyring" --populate archlinux cachyos
gpgconf --homedir "$keyring" --kill all
find "$keyring" -type s -delete

install -Dm644 /etc/pacman.conf "$rootfs/etc/pacman.conf"
install -Dm644 /etc/pacman.d/mirrorlist "$rootfs/etc/pacman.d/mirrorlist"
install -Dm644 /etc/pacman.d/cachyos-mirrorlist "$rootfs/etc/pacman.d/cachyos-mirrorlist"
install -Dm644 /etc/pacman.d/cachyos-v3-mirrorlist "$rootfs/etc/pacman.d/cachyos-v3-mirrorlist"

rm -rf \
    "$rootfs/var/cache"/* \
    "$rootfs/var/db" \
    "$rootfs/var/log/pacman.log" \
    "$rootfs/usr/include" \
    "$rootfs/usr/lib/"{cmake,pkgconfig} \
    "$rootfs/usr/share/"{aclocal,doc,factory,i18n,info} \
    "$rootfs/usr/share/"{licenses,locale,man,pixmaps,readline}

find "$rootfs" -type f \( -name '*.a' -o -name '*.o' -o -name '*.debug' \) -delete
rm -rf "$rootfs/var/log"/* "$rootfs/var/tmp"/*

mkfs.erofs \
    -x-1 \
    --all-root \
    -z zstd,level=3 \
    -C 1048576 \
    -Eall-fragments,dedupe \
    "$partial" "$rootfs/"
mv "$partial" "$archive"
