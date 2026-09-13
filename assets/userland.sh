#!/usr/bin/env bash
set -euo pipefail

archive="/output/${1:?missing output filename}"
packages=(
    base
    fastfetch
    hyfetch
    cachyos-keyring
    cachyos-mirrorlist
    cachyos-v3-mirrorlist
    fuse2fs
    fuse-overlayfs
    python
    dbus-broker-units
    networkmanager
    less
    dropbear
)

rootfs=$(mktemp -d)
partial="$archive.part"
trap 'rm -rf -- "$rootfs" "$partial"' EXIT

keyring="$rootfs/etc/pacman.d/gnupg"
chmod 0755 "$rootfs"

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

printf 'root:%s\n' '123456' | chroot "$rootfs" /usr/bin/chpasswd
sed -i '/^hosts:/c\hosts: files dns' "$rootfs/etc/nsswitch.conf"
grep -qx 'hosts: files dns' "$rootfs/etc/nsswitch.conf"

install -d -m 0700 "$keyring"
pacman-key --gpgdir "$keyring" --init
pacman-key --gpgdir "$keyring" \
    --populate-from "$rootfs/usr/share/pacman/keyrings" \
    --populate archlinux cachyos
gpgconf --homedir "$keyring" --kill all
find "$keyring" -type s -delete

install -Dm644 /etc/pacman.conf "$rootfs/etc/pacman.conf"
install -Dm644 /etc/pacman.d/mirrorlist "$rootfs/etc/pacman.d/mirrorlist"
install -Dm644 /etc/pacman.d/cachyos-mirrorlist "$rootfs/etc/pacman.d/cachyos-mirrorlist"
install -Dm644 /etc/pacman.d/cachyos-v3-mirrorlist "$rootfs/etc/pacman.d/cachyos-v3-mirrorlist"

rm -rf \
    "$rootfs/var/cache"/* \
    "$rootfs/var/log"/* \
    "$rootfs/var/tmp"/* \
    "$rootfs/usr/include" \
    "$rootfs/usr/lib/"{cmake,pkgconfig} \
    "$rootfs/usr/share/"{aclocal,doc,i18n,info} \
    "$rootfs/usr/share/"{licenses,locale,man,pixmaps,readline}

find "$rootfs/usr" -type f \( -name '*.a' -o -name '*.o' -o -name '*.debug' \) -delete
cp -a --no-preserve=ownership /usr/local/share/cpos/rootfs/. "$rootfs/"
systemctl --root="$rootfs" enable NetworkManager.service dropbear.service

mkfs.erofs \
    -x-1 \
    -z zstd,level=3 \
    -C 1048576 \
    -Eall-fragments,dedupe \
    "$partial" "$rootfs/"
mv "$partial" "$archive"
