# Third-party notices

PyP6's own license is in [LICENSE](LICENSE). The downloads also contain the
following third-party software, each under its own license. The license
texts are on the linked project pages.

## Desktop downloads (Windows, macOS, Linux)

| Component | License | Project |
|---|---|---|
| **ffmpeg / ffprobe** (bundled programs, run as separate processes) | GPL-3.0 or later (the "gpl" builds used here) | https://ffmpeg.org - builds: [BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds) (Windows, Linux), [ffmpeg.martin-riedl.de](https://ffmpeg.martin-riedl.de) (macOS) |
| Python | PSF License 2.0 | https://www.python.org |
| Tcl/Tk (Tkinter) | Tcl/Tk license (BSD-style) | https://www.tcl-lang.org |
| NumPy (includes OpenBLAS) | BSD-3-Clause | https://numpy.org |
| soundfile (includes libsndfile) | BSD-3-Clause; libsndfile: LGPL-2.1 or later | https://github.com/bastibe/python-soundfile, https://libsndfile.github.io/libsndfile/ |
| sounddevice (includes PortAudio) | MIT; PortAudio: MIT | https://github.com/spatialaudio/python-sounddevice, https://www.portaudio.com |
| pydub | MIT | https://github.com/jiaaro/pydub |
| tkinterdnd2 (includes tkdnd) | MIT; tkdnd: BSD-style | https://github.com/Eliav2/tkinterdnd2 |
| audioop-lts | PSF License 2.0 | https://github.com/AbstractUmbra/audioop |
| Pillow | MIT-CMU (HPND) | https://python-pillow.org |
| PyInstaller bootloader | GPL-2.0 with an exception that allows distributing the bundled program under any license | https://pyinstaller.org |

**ffmpeg source code:** ffmpeg is free software under the GNU GPL, bundled
unmodified as separate programs. Its source code is available from
https://ffmpeg.org; the build pages linked above describe how each build is
made.

## Android app (APK)

| Component | License | Project |
|---|---|---|
| AndroidX (Core, Activity, Lifecycle, Navigation, DocumentFile) and Jetpack Compose (UI, Foundation, Material 3, Material Icons) | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 | https://kotlinlang.org |
| XZ for Java | 0BSD | https://tukaani.org/xz/java.html |

## Concepts

The Chop feature is inspired by the concept of
[p6-wave-slice](https://github.com/warreneblackwell/p6-wave-slice) by Warren
Blackwell.
