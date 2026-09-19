use anyhow::{ensure, Context, Result};
use gpt::{disk::LogicalBlockSize, mbr::ProtectiveMBR, partition_types::Type, GptConfig};
use std::{
    collections::{BTreeMap, HashSet},
    fs::File,
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
};
use tempfile::NamedTempFile;
use uuid::Uuid;
use walkdir::WalkDir;

pub struct ImageBuilder {
    pub output: PathBuf,
    pub alignment: u64,
    pub partitions: Vec<Partition>,
}

pub struct Partition {
    pub name: String,
    pub id: Uuid,
    pub type_id: Option<Type>,
    pub size: u64,
    pub preserve: bool,
    pub read_only: bool,
    pub content: Content,
}

pub enum Content {
    Empty,
    Fat { directory: PathBuf },
    Image { path: PathBuf },
}

impl ImageBuilder {
    pub fn build(&self) -> Result<()> {
        ensure!(
            self.alignment >= 512 && self.alignment.is_power_of_two(),
            "Invalid alignment"
        );
        ensure!(
            !self.partitions.is_empty() && self.partitions.len() <= u32::MAX as usize,
            "Invalid partition count"
        );
        let parent = self
            .output
            .parent()
            .filter(|path| !path.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        std::fs::create_dir_all(parent)?;
        let mut ids = HashSet::new();
        let table_bytes = (self.partitions.len().max(128) as u64 * 128).div_ceil(512) * 512;
        let mut size = self.align(table_bytes + 1024)?;
        let mut layout = BTreeMap::new();
        for (index, partition) in self.partitions.iter().enumerate() {
            ensure!(
                partition.size > 0 && partition.size % 512 == 0,
                "Invalid partition size"
            );
            ensure!(
                !partition.id.is_nil() && ids.insert(partition.id),
                "Duplicate or empty partition UUID"
            );
            ensure!(
                partition.name.encode_utf16().count() <= 36,
                "GPT partition name is too long"
            );
            let start = self.align(size)?;
            size = start
                .checked_add(partition.size)
                .context("Disk size overflow")?;
            layout.insert(
                index as u32 + 1,
                gpt::partition::Partition {
                    part_guid: partition.id,
                    part_type_guid: partition
                        .type_id
                        .clone()
                        .unwrap_or(match partition.content {
                            Content::Fat { .. } => gpt::partition_types::EFI,
                            _ => gpt::partition_types::LINUX_FS,
                        }),
                    first_lba: start / 512,
                    last_lba: size / 512 - 1,
                    flags: if partition.read_only { 1 << 60 } else { 0 },
                    name: partition.name.clone(),
                },
            );
        }
        size = self.align(
            size.checked_add(table_bytes + 512)
                .context("Disk size overflow")?,
        )?;
        let mut target = NamedTempFile::new_in(parent)?;
        target.as_file().set_len(size)?;
        ProtectiveMBR::with_lb_size((size / 512 - 1).min(u32::MAX as u64) as u32)
            .overwrite_lba0(target.as_file_mut())?;
        let mut disk = GptConfig::new()
            .writable(true)
            .change_partition_count(true)
            .logical_block_size(LogicalBlockSize::Lb512)
            .create_from_device(Box::new(target.as_file_mut()), None)?;
        disk.update_partitions(layout.clone())?;
        disk.write()?;
        let previous = if self.output.exists() && self.partitions.iter().any(|part| part.preserve) {
            Some(
                GptConfig::new()
                    .open(&self.output)
                    .context("Cannot preserve invalid disk")?,
            )
        } else {
            None
        };
        for (index, partition) in self.partitions.iter().enumerate() {
            let entry = &layout[&(index as u32 + 1)];
            let start = entry.bytes_start(LogicalBlockSize::Lb512)?;
            if let Some(previous) = previous.as_ref().filter(|_| partition.preserve) {
                let old = previous
                    .partitions()
                    .values()
                    .find(|old| old.part_guid == entry.part_guid)
                    .context("Persistent partition missing; explicit migration required")?;
                ensure!(
                    old.first_lba == entry.first_lba
                        && old.last_lba == entry.last_lba
                        && old.part_type_guid == entry.part_type_guid,
                    "Persistent partition layout changed; explicit migration required"
                );
                Self::copy_sparse(
                    &mut File::open(&self.output)?,
                    start,
                    target.as_file_mut(),
                    start,
                    partition.size,
                )?;
                continue;
            }
            match &partition.content {
                Content::Empty => {}
                Content::Image { path } => {
                    let mut source = File::open(path)?;
                    let length = source.metadata()?.len();
                    ensure!(
                        length <= partition.size,
                        "{} exceeds partition capacity",
                        path.display()
                    );
                    Self::copy_sparse(&mut source, 0, target.as_file_mut(), start, length)?;
                }
                Content::Fat { directory } => {
                    let mut source = NamedTempFile::new_in(parent)?;
                    source.as_file().set_len(partition.size)?;
                    fatfs::format_volume(
                        source.as_file_mut(),
                        fatfs::FormatVolumeOptions::new().fat_type(fatfs::FatType::Fat32),
                    )?;
                    let filesystem =
                        fatfs::FileSystem::new(source.as_file_mut(), fatfs::FsOptions::new())?;
                    let root = filesystem.root_dir();
                    for item in WalkDir::new(directory).sort_by_file_name().min_depth(1) {
                        let item = item?;
                        let relative = item
                            .path()
                            .strip_prefix(directory)?
                            .to_str()
                            .context("Invalid FAT path")?;
                        ensure!(
                            !item.file_type().is_symlink(),
                            "FAT content cannot contain symlinks"
                        );
                        if item.file_type().is_dir() {
                            root.create_dir(relative)?;
                            continue;
                        }
                        let mut file = root.create_file(relative)?;
                        std::io::copy(&mut File::open(item.path())?, &mut file)?;
                    }
                    drop(root);
                    filesystem.unmount()?;
                    Self::copy_sparse(
                        source.as_file_mut(),
                        0,
                        target.as_file_mut(),
                        start,
                        partition.size,
                    )?;
                }
            }
        }
        target.as_file().sync_all()?;
        target.persist(&self.output)?;
        File::open(parent)?.sync_all()?;
        Ok(())
    }

    fn align(&self, value: u64) -> Result<u64> {
        Ok(value
            .checked_add(self.alignment - 1)
            .context("Alignment overflow")?
            & !(self.alignment - 1))
    }

    fn copy_sparse(
        source: &mut File,
        source_offset: u64,
        target: &mut File,
        target_offset: u64,
        length: u64,
    ) -> Result<()> {
        source.seek(SeekFrom::Start(source_offset))?;
        let mut buffer = vec![0u8; 1024 * 1024];
        let mut completed = 0;
        while completed < length {
            let count = (length - completed).min(buffer.len() as u64) as usize;
            source.read_exact(&mut buffer[..count])?;
            for (index, chunk) in buffer[..count].chunks(4096).enumerate() {
                if chunk.iter().all(|byte| *byte == 0) {
                    continue;
                }
                target.seek(SeekFrom::Start(
                    target_offset + completed + (index * 4096) as u64,
                ))?;
                target.write_all(chunk)?;
            }
            completed += count as u64;
        }
        Ok(())
    }
}
