# OIB

Sparse GPT image builder, derived from [oib 0.3.0](https://crates.io/crates/oib/0.3.0).
The vendored version supports multiple partitions through a `lexopt` command-line interface.

Build with `cargo build --locked --release`:

```sh
oib --output CoolPotOS.img \
    --partition EFI ee336264-d9b3-4b09-a203-b46e0339701b 134217728 --fat esp \
    --partition rootfs 8b849e5c-c8f5-4dc5-aab9-730f07b14f30 1073741824 --image rootfs.erofs --read-only \
    --partition overlay 4f68ed11-7e90-4587-9dd9-320f0c830153 2147483648 --image overlay.ext4 --preserve
```

Each `--partition NAME UUID BYTES` starts a partition. Its following options select
FAT32 directory content (`--fat`), image content (`--image`), or an empty partition
when neither is supplied. FAT32 defaults to the EFI partition type; other content
uses the Linux filesystem type. `--type UUID` overrides either default.

Sizes are bytes and must be sector aligned. Images use 512-byte logical sectors
and 1 MiB alignment, configurable with `--alignment BYTES`. Other filesystems are
prepared externally. Paths are individual arguments and need no configuration escaping.

Rebuilding preserves partitions marked `--preserve` by UUID and requires their layout
and type to remain unchanged. The completed sparse image atomically replaces the
output. Stop the guest before rebuilding its image.

`cargo test --locked` validates command-line arguments, sparse allocation, FAT32
content, preservation, and refusal of incompatible persistent partition layouts.
