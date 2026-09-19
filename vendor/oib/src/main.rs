use anyhow::{ensure, Context, Result};
use lexopt::prelude::*;
use oib::{Content, ImageBuilder, Partition};
use std::path::PathBuf;

fn main() -> Result<()> {
    let mut arguments = lexopt::Parser::from_env();
    let mut output: Option<PathBuf> = None;
    let mut alignment = 1024 * 1024;
    let mut partitions: Vec<Partition> = Vec::new();
    while let Some(argument) = arguments.next()? {
        match argument {
            Short('h') | Long("help") => {
                println!(
                    "Usage: oib --output PATH [--alignment BYTES] --partition NAME UUID BYTES [OPTIONS] ...

Each --partition starts a new GPT partition. Its options are:
  --fat DIRECTORY   Format FAT32 and copy a directory tree (default type: EFI)
  --image PATH      Copy a filesystem image (default type: Linux filesystem)
  --type UUID       Override the GPT partition type
  --preserve        Preserve matching contents from the existing output
  --read-only       Set the GPT read-only attribute

Without --fat or --image, the partition is empty. Sizes are bytes.
Output is sparse raw with 512-byte sectors and 1 MiB alignment by default."
                );
                return Ok(());
            }
            Short('o') | Long("output") => output = Some(arguments.value()?.into()),
            Long("alignment") => alignment = arguments.value()?.parse()?,
            Long("partition") => partitions.push(Partition {
                name: arguments.value()?.string()?,
                id: arguments.value()?.parse()?,
                size: arguments.value()?.parse()?,
                type_id: None,
                preserve: false,
                read_only: false,
                content: Content::Empty,
            }),
            Long(option @ ("fat" | "image" | "type" | "preserve" | "read-only")) => {
                let partition = partitions
                    .last_mut()
                    .context("Expected --partition first")?;
                match option {
                    "preserve" => partition.preserve = true,
                    "read-only" => partition.read_only = true,
                    "type" => partition.type_id = Some(arguments.value()?.parse()?),
                    _ => {
                        ensure!(
                            matches!(partition.content, Content::Empty),
                            "Duplicate partition content"
                        );
                        let fat = option == "fat";
                        let path = arguments.value()?.into();
                        partition.content = if fat {
                            Content::Fat { directory: path }
                        } else {
                            Content::Image { path }
                        };
                    }
                }
            }
            _ => return Err(argument.unexpected().into()),
        }
    }
    let builder = ImageBuilder {
        output: output.context("Expected --output PATH")?,
        alignment,
        partitions,
    };
    builder.build()?;
    println!("Created {}", builder.output.display());
    Ok(())
}
