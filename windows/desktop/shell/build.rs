// Embeds Codio's icon, version information and application manifest in Codio.exe.
// Needs a GNU-style windres: set WINDRES to its path, or have `windres` on PATH.
use std::{env, fs, path::PathBuf, process::Command};

fn main() {
    println!("cargo:rerun-if-changed=res");
    println!("cargo:rerun-if-env-changed=WINDRES");
    let version = env!("CARGO_PKG_VERSION");
    let numbers: Vec<u32> = version.split('.').map(|part| part.parse().unwrap_or(0)).collect();
    let comma = format!("{},{},{},0", numbers[0], numbers.get(1).unwrap_or(&0), numbers.get(2).unwrap_or(&0));
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let res = manifest_dir.join("res");
    let path = |name: &str| res.join(name).to_string_lossy().replace('\\', "/");
    let rc = format!(
        r#"#include <winver.h>
1 ICON "{icon}"
1 24 "{manifest}"
VS_VERSION_INFO VERSIONINFO
FILEVERSION {comma}
PRODUCTVERSION {comma}
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "040904B0"
    BEGIN
      VALUE "CompanyName", "Codio"
      VALUE "FileDescription", "Codio"
      VALUE "FileVersion", "{version}"
      VALUE "LegalCopyright", "Codio, GPL-2.0-or-later"
      VALUE "OriginalFilename", "Codio.exe"
      VALUE "ProductName", "Codio"
      VALUE "ProductVersion", "{version}"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x409, 1200
  END
END
"#,
        icon = path("codio.ico"),
        manifest = path("codio.manifest"),
    );
    let rc_file = out_dir.join("codio.rc");
    fs::write(&rc_file, rc).unwrap();
    let object = out_dir.join("codio-res.o");
    let windres = env::var("WINDRES").unwrap_or_else(|_| "windres".into());
    let status = Command::new(&windres)
        .arg("-O")
        .arg("coff")
        .arg("-i")
        .arg(&rc_file)
        .arg("-o")
        .arg(&object)
        .status()
        .unwrap_or_else(|error| panic!("cannot run {windres}: {error}"));
    assert!(status.success(), "{windres} failed");
    println!("cargo:rustc-link-arg={}", object.display());
}