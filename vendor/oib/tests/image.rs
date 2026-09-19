use oib::{Content, ImageBuilder, Partition};
use std::{
    fs::{self, File},
    io::{Read, Seek, SeekFrom, Write},
    os::unix::fs::MetadataExt,
};
use tempfile::tempdir;

#[test]
fn sparse_partitions_preserve_data_and_reject_layout_changes() {
    let directory = tempdir().unwrap();
    let seed = directory.path().join("seed");
    fs::write(&seed, [0x42; 512]).unwrap();
    let mut builder = ImageBuilder {
        output: directory.path().join("disk.img"),
        alignment: 1024 * 1024,
        partitions: vec![Partition {
            name: "data".into(),
            id: "455a7f41-d0d3-40ac-9051-5414fb7b15c9".parse().unwrap(),
            type_id: None,
            size: 16 * 1024 * 1024,
            preserve: true,
            read_only: false,
            content: Content::Image { path: seed },
        }],
    };
    builder.build().unwrap();
    let metadata = builder.output.metadata().unwrap();
    assert!(metadata.blocks() * 512 < metadata.len() / 4);
    let disk = gpt::GptConfig::new().open(&builder.output).unwrap();
    let part = disk.partitions().get(&1).unwrap();
    assert_eq!(part.first_lba, 2048);
    let mut file = File::options()
        .read(true)
        .write(true)
        .open(&builder.output)
        .unwrap();
    file.seek(SeekFrom::Start(part.first_lba * 512 + 4096))
        .unwrap();
    file.write_all(b"persistent").unwrap();
    drop(file);
    builder.build().unwrap();
    let mut file = File::open(&builder.output).unwrap();
    file.seek(SeekFrom::Start(part.first_lba * 512 + 4096))
        .unwrap();
    let mut actual = [0; 10];
    file.read_exact(&mut actual).unwrap();
    assert_eq!(&actual, b"persistent");
    builder.partitions[0].size *= 2;
    assert!(builder.build().is_err());
    assert_eq!(builder.output.metadata().unwrap().len(), metadata.len());
}

#[test]
fn fat32_esp_contains_nested_boot_file() {
    let directory = tempdir().unwrap();
    let esp = directory.path().join("esp");
    fs::create_dir_all(esp.join("EFI/BOOT")).unwrap();
    fs::write(esp.join("EFI/BOOT/BOOTX64.EFI"), b"loader").unwrap();
    let output = directory.path().join("disk.img");
    let result = std::process::Command::new(env!("CARGO_BIN_EXE_oib"))
        .arg("--output")
        .arg(&output)
        .args([
            "--partition",
            "EFI",
            "69764701-1e65-40ca-b863-117ed9b50f43",
            "67108864",
            "--fat",
        ])
        .arg(&esp)
        .output()
        .unwrap();
    assert!(result.status.success(), "{:?}", result);
    let disk = gpt::GptConfig::new().open(&output).unwrap();
    assert_eq!(
        disk.partitions()[&1].part_type_guid,
        gpt::partition_types::EFI
    );
    let image = fs::read(&output).unwrap();
    let mut source = std::io::Cursor::new(image[1024 * 1024..65 * 1024 * 1024].to_vec());
    let fat = fatfs::FileSystem::new(&mut source, fatfs::FsOptions::new()).unwrap();
    assert_eq!(fat.fat_type(), fatfs::FatType::Fat32);
    let mut file = fat.root_dir().open_file("EFI/BOOT/BOOTX64.EFI").unwrap();
    let mut data = Vec::new();
    file.read_to_end(&mut data).unwrap();
    assert_eq!(data, b"loader");
}

#[test]
fn partition_table_expands_beyond_default_capacity() {
    let directory = tempdir().unwrap();
    let builder = ImageBuilder {
        output: directory.path().join("disk.img"),
        alignment: 512,
        partitions: (1..=129)
            .map(|index| Partition {
                name: index.to_string(),
                id: uuid::Uuid::from_u128(index),
                type_id: None,
                size: 512,
                preserve: false,
                read_only: false,
                content: Content::Empty,
            })
            .collect(),
    };
    builder.build().unwrap();
    let disk = gpt::GptConfig::new().open(&builder.output).unwrap();
    assert_eq!(disk.partitions().len(), 129);
    assert!(disk.partitions()[&1].first_lba >= disk.primary_header().unwrap().first_usable);
    assert!(disk.partitions()[&129].last_lba <= disk.primary_header().unwrap().last_usable);
}

#[test]
fn cli_builds_multiple_partitions_with_literal_paths_and_preserves_data() {
    let directory = tempdir().unwrap();
    let output = directory.path().join("disk 'quoted' 雪.img");
    let seed = directory.path().join("source \"data\" \\ file");
    fs::write(&seed, b"original").unwrap();
    let mut command = std::process::Command::new(env!("CARGO_BIN_EXE_oib"));
    command
        .arg("--output")
        .arg(&output)
        .args([
            "--alignment",
            "4096",
            "--partition",
            "root",
            "455a7f41-d0d3-40ac-9051-5414fb7b15c9",
            "65536",
            "--read-only",
            "--image",
        ])
        .arg(&seed)
        .args([
            "--preserve",
            "--type",
            "0fc63daf-8483-4772-8e79-3d69d8477de4",
            "--partition",
            "scratch",
            "69764701-1e65-40ca-b863-117ed9b50f43",
            "65536",
        ]);
    let result = command.output().unwrap();
    assert!(result.status.success(), "{:?}", result);
    let disk = gpt::GptConfig::new().open(&output).unwrap();
    assert_eq!(disk.partitions().len(), 2);
    let first = &disk.partitions()[&1];
    let second = &disk.partitions()[&2];
    assert_eq!(first.name, "root");
    assert_eq!(first.flags, 1 << 60);
    assert_eq!(first.part_type_guid, gpt::partition_types::LINUX_FS);
    assert_eq!(second.flags, 0);
    assert_eq!(second.name, "scratch");
    assert_eq!(first.first_lba * 512 % 4096, 0);
    assert_eq!(second.first_lba * 512 % 4096, 0);
    fs::write(&seed, b"modified").unwrap();
    assert!(command.output().unwrap().status.success());
    let mut file = File::open(&output).unwrap();
    file.seek(SeekFrom::Start(first.first_lba * 512)).unwrap();
    let mut bytes = [0; 8];
    file.read_exact(&mut bytes).unwrap();
    assert_eq!(&bytes, b"original");
    file.seek(SeekFrom::Start(second.first_lba * 512)).unwrap();
    file.read_exact(&mut bytes).unwrap();
    assert_eq!(bytes, [0; 8]);
    let metadata = output.metadata().unwrap();
    assert!(metadata.blocks() * 512 < metadata.len() / 2);
}

#[test]
fn cli_rejects_invalid_arguments_without_replacing_output() {
    let directory = tempdir().unwrap();
    let output = directory.path().join("disk.img");
    fs::write(&output, b"untouched").unwrap();
    let invalid: &[&[&str]] = &[
        &[],
        &["--unknown"],
        &["--alignment", "invalid"],
        &["--preserve"],
        &["--partition", "data"],
        &["--partition", "data", "invalid", "65536"],
        &[
            "--partition",
            "data",
            "455a7f41-d0d3-40ac-9051-5414fb7b15c9",
            "invalid",
        ],
        &[
            "--partition",
            "data",
            "455a7f41-d0d3-40ac-9051-5414fb7b15c9",
            "65536",
            "--type",
            "invalid",
        ],
        &[
            "--partition",
            "data",
            "455a7f41-d0d3-40ac-9051-5414fb7b15c9",
            "65536",
            "--image",
            "one",
            "--fat",
            "two",
        ],
    ];
    for arguments in invalid {
        let result = std::process::Command::new(env!("CARGO_BIN_EXE_oib"))
            .arg("--output")
            .arg(&output)
            .args(*arguments)
            .output()
            .unwrap();
        assert!(!result.status.success(), "{:?}", arguments);
        assert_eq!(fs::read(&output).unwrap(), b"untouched");
    }
}
