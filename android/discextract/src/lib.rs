//! Extracts a Wii disc image into the files/ and sys/ folders the engine reads.
//!
//! The engine opens the game with ordinary file calls, so it needs a disc that has already been
//! taken apart. On a PC the launcher shells out to nodtool for that. A phone has no such
//! executable to run, so the library nodtool is built on is linked into the app and driven from
//! here, through one JNI call.
//!
//! The image is read through a file descriptor rather than a path. Android hands an app a
//! `content://` URI for a file the user picked, which no path-based API can open, and the
//! alternative - copying several gigabytes into app storage first, only to delete it after - is
//! not one worth offering. `DiscStream` reads by absolute offset, so a descriptor is all it needs.

use std::fs;
use std::io;
use std::os::fd::FromRawFd;
use std::os::unix::fs::FileExt;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

use nod::common::PartitionKind;
use nod::read::{DiscOptions, DiscReader, DiscStream, PartitionOptions};

/// The image, as nod reads it: by absolute offset, never by a seek position of its own.
///
/// Cloneable because nod clones the stream to read partitions in parallel. Cloning shares the one
/// descriptor, which is safe precisely because every read states its own offset.
#[derive(Clone)]
struct FdStream {
    file: Arc<fs::File>,
    len: u64,
}

impl DiscStream for FdStream {
    fn read_exact_at(&mut self, buf: &mut [u8], offset: u64) -> io::Result<()> {
        self.file.read_exact_at(buf, offset)
    }

    fn stream_len(&mut self) -> io::Result<u64> {
        Ok(self.len)
    }
}

/// What the Java side is told while this runs, and how it says to stop.
struct Progress<'a, 'b> {
    env: &'a mut JNIEnv<'b>,
    listener: &'a JObject<'b>,
}

impl Progress<'_, '_> {
    fn report(&mut self, done: u64, total: u64, name: &str) -> Result<(), String> {
        let jname = self
            .env
            .new_string(name)
            .map_err(|e| format!("Could not report progress: {e}"))?;
        self.env
            .call_method(
                self.listener,
                "onProgress",
                "(JJLjava/lang/String;)V",
                &[
                    JValue::Long(done as i64),
                    JValue::Long(total as i64),
                    JValue::Object(&jname),
                ],
            )
            .map_err(|e| format!("Could not report progress: {e}"))?;
        Ok(())
    }

    fn cancelled(&mut self) -> bool {
        self.env
            .call_method(self.listener, "isCancelled", "()Z", &[])
            .and_then(|v| v.z())
            .unwrap_or(false)
    }
}

/// Rejects anything in the disc's own file table that would write outside the destination.
///
/// A disc is user-supplied data, and its file table is just names: one containing a parent
/// reference, or an absolute path, would otherwise place a file anywhere the app can write.
/// Nothing legitimate needs either, so both are refused rather than sanitised.
fn safe_join(root: &Path, relative: &str) -> Result<PathBuf, String> {
    let mut path = root.to_path_buf();
    for part in relative.split('/') {
        if part.is_empty() || part == "." {
            continue;
        }
        let candidate = Path::new(part);
        let mut components = candidate.components();
        match (components.next(), components.next()) {
            (Some(Component::Normal(name)), None) => path.push(name),
            _ => return Err(format!("The disc contains an unusable file name: {relative}")),
        }
    }
    Ok(path)
}

fn write_file(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("Cannot create {}: {e}", parent.display()))?;
    }
    fs::write(path, bytes).map_err(|e| format!("Cannot write {}: {e}", path.display()))
}

/// Everything after the descriptor has been taken: the part that can fail with a message.
fn extract(file: fs::File, dest: &Path, progress: &mut Progress) -> Result<(), String> {
    let len = file
        .metadata()
        .map_err(|e| format!("Cannot read the image: {e}"))?
        .len();
    if len == 0 {
        return Err("That file is empty.".to_string());
    }
    let stream = FdStream {
        file: Arc::new(file),
        len,
    };

    let disc = DiscReader::new_stream(Box::new(stream), &DiscOptions::default())
        .map_err(|e| format!("That is not a disc image this can read: {e}"))?;
    let mut partition = disc
        .open_partition_kind(PartitionKind::Data, &PartitionOptions::default())
        .map_err(|e| format!("The disc has no readable data partition: {e}"))?;
    let meta = partition
        .meta()
        .map_err(|e| format!("The disc's header could not be read: {e}"))?;

    // sys/: the pieces that live outside the file table. Named as Dolphin's "Extract Entire Disc"
    // names them, because that is the layout the engine and the import check already expect.
    let sys = dest.join("sys");
    write_file(&sys.join("boot.bin"), meta.raw_boot.as_ref())?;
    write_file(&sys.join("bi2.bin"), meta.raw_bi2.as_ref())?;
    write_file(&sys.join("apploader.img"), meta.raw_apploader.as_ref())?;
    write_file(&sys.join("main.dol"), meta.raw_dol.as_ref())?;
    write_file(&sys.join("fst.bin"), meta.raw_fst.as_ref())?;

    let fst = meta
        .fst()
        .map_err(|e| format!("The disc's file table is unreadable: {e}"))?;

    // Two passes over the file table: the first only to total the bytes, so the progress the
    // player watches is against the real figure rather than a guess that jumps at the end.
    let total: u64 = fst
        .iter()
        .filter(|(_, node, _)| node.is_file())
        .map(|(_, node, _)| node.length() as u64)
        .sum();

    let files = dest.join("files");
    fs::create_dir_all(&files).map_err(|e| format!("Cannot create {}: {e}", files.display()))?;

    let mut done = 0u64;
    for (_, node, path) in fst.iter() {
        if progress.cancelled() {
            // Empty message: cancelling is the player's doing, not a failure to report.
            return Err(String::new());
        }
        let target = safe_join(&files, &path)?;
        if node.is_dir() {
            fs::create_dir_all(&target)
                .map_err(|e| format!("Cannot create {}: {e}", target.display()))?;
            continue;
        }
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("Cannot create {}: {e}", parent.display()))?;
        }
        let mut reader = partition
            .open_file(node)
            .map_err(|e| format!("Cannot read {path} from the disc: {e}"))?;
        let mut out = fs::File::create(&target)
            .map_err(|e| format!("Cannot write {}: {e}", target.display()))?;
        io::copy(&mut reader, &mut out)
            .map_err(|e| format!("Cannot write {}: {e}", target.display()))?;
        done += node.length() as u64;
        progress.report(done, total, &path)?;
    }
    Ok(())
}

/// DiscExtract.nativeExtract: the whole extraction, start to finish, on the calling thread.
///
/// Returns null when the disc is out, or a sentence to show the player. Cancelling returns an
/// empty string, which the Java side knows not to treat as an error.
///
/// Takes ownership of `fd` - the caller must have detached it from its ParcelFileDescriptor - and
/// closes it on the way out.
#[no_mangle]
pub extern "system" fn Java_com_santiago109_wariorecomp_DiscExtract_nativeExtract<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    dest: JString<'local>,
    listener: JObject<'local>,
) -> jstring {
    // A panic unwinding across the JNI boundary is undefined behaviour, and this code runs over
    // whatever file table a disc the player chose happens to contain.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if fd < 0 {
            return Err("That file could not be opened.".to_string());
        }
        let dest: String = env
            .get_string(&dest)
            .map_err(|e| format!("Bad destination path: {e}"))?
            .into();
        // SAFETY: the Java side detached this descriptor, so nothing else owns it, and the File
        // closes it when this returns.
        let file = unsafe { fs::File::from_raw_fd(fd) };
        let mut progress = Progress {
            env: &mut env,
            listener: &listener,
        };
        extract(file, Path::new(&dest), &mut progress)
    }));

    let message = match result {
        Ok(Ok(())) => return std::ptr::null_mut(),
        Ok(Err(message)) => message,
        Err(_) => "The extraction stopped unexpectedly.".to_string(),
    };
    env.new_string(message)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
