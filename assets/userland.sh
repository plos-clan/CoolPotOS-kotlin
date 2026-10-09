#!/usr/bin/env bash
set -euo pipefail

archive="/output/${1:?missing output filename}"
pacman=(pacman --noconfirm --disable-sandbox-network)
packages=(
    base
    fastfetch
    hyfetch
    cachyos-keyring
    cachyos-mirrorlist
    cachyos-v3-mirrorlist
    e2fsprogs
    ethtool
    fuse2fs
    fuse-overlayfs
    python
    dbus-broker-units
    networkmanager
    less
    dropbear
    noctalia
    greetd
    polkit
    noctalia-greeter
    foot
    nemo
    ttf-dejavu
)

rootfs=$(mktemp -d)
build=$(mktemp -d)
partial="$archive.part"
trap 'rm -rf -- "$rootfs" "$build" "$partial"' EXIT

chmod 0755 "$rootfs"

sed -i '/^\[cachyos\]$/i\
[cachyos-v3]\
Include = /etc/pacman.d/cachyos-v3-mirrorlist\
[cachyos-core-v3]\
Include = /etc/pacman.d/cachyos-v3-mirrorlist\
[cachyos-extra-v3]\
Include = /etc/pacman.d/cachyos-v3-mirrorlist\
' /etc/pacman.conf
printf 'Server = https://mirrors.sustech.edu.cn/archlinux/$repo/os/$arch\n' \
    > /etc/pacman.d/mirrorlist
printf 'Server = https://mirrors.sustech.edu.cn/cachyos/repo/$arch/$repo\n' \
    > /etc/pacman.d/cachyos-mirrorlist
printf 'Server = https://mirrors.sustech.edu.cn/cachyos/repo/$arch_v3/$repo\n' \
    > /etc/pacman.d/cachyos-v3-mirrorlist

"${pacman[@]}" -Syu --needed erofs-utils base-devel curl git
useradd --home-dir "$build" builder
chown builder:builder "$build"
printf 'builder ALL=(root) NOPASSWD: /usr/bin/pacman\n' >> /etc/sudoers
for package in xdg-desktop-portal-umbriel-git umbriel-git; do
    install -d -o builder -g builder "$build/$package"
    runuser -u builder -- curl -fsSL --retry 3 \
        "https://aur.archlinux.org/cgit/aur.git/plain/PKGBUILD?h=$package" \
        -o "$build/$package/PKGBUILD"
    runuser -u builder -- env PKGDEST="$build" \
        makepkg -si --noconfirm --dir "$build/$package"
done

mkdir -p "$rootfs/var/lib/pacman"
install -Dm644 /etc/os-release "$rootfs/etc/os-release"
"${pacman[@]}" --root "$rootfs" -Sy "${packages[@]}"
"${pacman[@]}" --root "$rootfs" -U "$build"/*.pkg.tar.zst

chroot "$rootfs" /usr/bin/useradd -m -G wheel xiaoyi12
printf 'xiaoyi12:%s\n' '123456' | chroot "$rootfs" /usr/bin/chpasswd
sed -i '/^hosts:/c\hosts: files dns' "$rootfs/etc/nsswitch.conf"
grep -qx 'hosts: files dns' "$rootfs/etc/nsswitch.conf"

keyring="$rootfs/etc/pacman.d/gnupg"
install -d -m 0700 "$keyring"
pacman-key --gpgdir "$keyring" --init
pacman-key --gpgdir "$keyring" \
    --populate-from "$rootfs/usr/share/pacman/keyrings" \
    --populate archlinux cachyos
gpgconf --homedir "$keyring" --kill all
find "$keyring" -type s -delete

install -Dm644 /etc/pacman.conf "$rootfs/etc/pacman.conf"
install -Dm644 /etc/pacman.d/{mirrorlist,cachyos-mirrorlist,cachyos-v3-mirrorlist} \
    -t "$rootfs/etc/pacman.d"

mkdir -p "$rootfs/sysroot"
ln -s run/overlay "$rootfs/overlay"
ln -s os-release "$rootfs/etc/initrd-release"
install -Dm644 /usr/local/share/cpos/systemd/* -t "$rootfs/usr/lib/systemd/system"
install -Dm644 /dev/stdin "$rootfs/etc/NetworkManager/conf.d/10-dns.conf" <<'EOF'
[main]
systemd-resolved=false
EOF
install -Dm644 /dev/stdin "$rootfs/etc/environment" <<'EOF'
WLR_RENDERER_ALLOW_SOFTWARE=1
EOF
install -Dm644 /dev/stdin "$rootfs/etc/greetd/config.toml" <<'EOF'
[terminal]
vt = 1

[default_session]
command = "/usr/bin/noctalia-greeter-session"
user = "greeter"
service = "greetd"
EOF
install -Dm644 /dev/stdin "$rootfs/etc/xdg/umbriel/config.toml" <<'EOF'
[general]
autostart = ["noctalia"]

[keybinds]
"Mod+Q" = "window-close"
"Mod+Return" = "spawn:noctalia msg panel-toggle launcher"
"Mod+BackSlash" = "spawn:foot"
"Mod+BackSpace" = "spawn:nemo"
"Mod+C" = "spawn:noctalia msg panel-toggle clipboard"
"Mod+F" = "window-toggle-maximize"
"Mod+Shift+F" = "window-toggle-fullscreen"
"Mod+V" = "window-toggle-floating"
"Print" = "spawn:noctalia msg screenshot-region"
"Ctrl+Print" = "spawn:noctalia msg screenshot-fullscreen"

[include]
files = ["/usr/share/umbriel/config.toml"]

[include.optional]
files = ["noctalia.toml"]
EOF
install -Dm644 /dev/stdin "$rootfs/usr/lib/tmpfiles.d/cpos-pty.conf" <<'EOF'
z /dev/pts/ptmx 0666 root root -
EOF
systemctl --root="$rootfs" enable NetworkManager.service dropbear.service greetd.service

systemd-sysusers --root="$rootfs"
chroot "$rootfs" noctalia-greeter-apply-appearance --setup-system
systemd-tmpfiles --root="$rootfs" --create --boot --prefix=/etc --prefix=/var
ldconfig -r "$rootfs"
journalctl --root="$rootfs" --update-catalog

rm -rf \
    "$rootfs/var/cache"/* \
    "$rootfs/var/log"/* \
    "$rootfs/var/tmp"/* \
    "$rootfs/usr/include" \
    "$rootfs/usr/lib/"{cmake,pkgconfig} \
    "$rootfs/usr/share/"{aclocal,i18n,info,locale,man,readline}
find "$rootfs/usr" -type f \( -name '*.a' -o -name '*.o' -o -name '*.debug' \) -delete
/usr/lib/systemd/systemd-update-done --root="$rootfs"

mkfs.erofs \
    -x-1 \
    -z zstd,level=3 \
    -C 65536 \
    -Eall-fragments,dedupe \
    "$partial" "$rootfs/"
mv "$partial" "$archive"
