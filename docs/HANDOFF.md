# PyP6 handoff brief: patterns, P-6 transfer and releases (v4.2.3 → v5.0.0)

The work was merged in PR #1 (features) and PR #2 (version bump) and released
as **v5.0.0**, marked Latest, with all four downloads and the release notes
from `.github/release-notes/v5.0.0.md`.

Line numbers below are approximate, as of v5.0.0.

## What was built

1. **All banks + patterns view**, a third view mode:
   - **Layout:** the 8×6 sample grid in "slim" form (waveform plus ▶ only),
     with a 4×16 pattern strip and an info card for the selected pattern
     underneath.
   - **Pattern ↔ pad highlight:** selecting a pattern highlights the pads it
     plays; selecting a pad highlights the patterns that play it.
   - **Pattern editing:** drag to swap or move patterns, and **Clear**.
     Everything is undoable with Ctrl+Z.
   - **Empty pads:** clicking one selects it and makes its bank the current
     one.
2. **Sync patterns with pad moves** (a toggle, off by default, saved in
   config as `pattern_sync`). Pad swaps, bank swaps and "Move To" bank
   rewrite the loaded patterns: note PARTs, `GRANU_PHRASE`,
   `MOTION_PRMn_PART`, and the per-part lists
   `PART_MUTE`/`PART_QUANTIZE`/`RESERVED_PTN1`.
3. **P-6 transfer:** `PatternDeviceDialog` shows the numbered steps and keeps
   searching for the drive until it appears.
   - Backup (load) reads the `BACKUP` folder; hold **[▶] PLAY** at power-on.
   - Restore (save) writes the `RESTORE` folder; hold **[●] REC** at
     power-on, then eject and press **[KYBD]**.
   - A folder on the computer works as an alternative in both directions.
4. **Presets:** a "Patterns 1–4" tickbox row. Patterns are stored in
   `PATTERNS/` inside the preset folder, and the manifest gets a
   `patterns.slots` entry. Preset format is now **v4**.
5. **Releases:** `.github/workflows/release.yml` builds Windows x64, macOS
   arm64, macOS x64 and Linux x64 with PyInstaller, and bundles
   ffmpeg/ffprobe. The committed Windows zip was removed from the repo.

## Key architectural decisions

- **Pattern text is kept verbatim.** `P6Pattern` is immutable; `remap_parts()`
  and `cleared()` return new objects and only rewrite the numbers that
  identify a pad. Because of that, undo snapshots store a shallow
  `dict(self.patterns)`.
- **PART mapping is one constant:** `PATTERN_PART_BASE = 0`, meaning A1=0 …
  H6=47, with 48 as the granular part. This is **unconfirmed on hardware**;
  if it's wrong, only this value needs changing.
- **Clear matches the device.** A device "clear all" produced a
  byte-identical file: settings are kept and only step lines are blanked. The
  step lines come from the embedded device file `_BLANK_PATTERN_XZ`. A slot
  with no file gets that whole file.
- **Missing file ≠ empty pattern.** A restore leaves slots without a file
  untouched on the P-6. A cleared pattern is a real file, so it overwrites
  the slot.
- **Views:** `_is_overview()` covers the `"all"` and `"patterns"` modes.
  `CompactSlot.set_slim()` handles the slim layout; `_layout_rows()` decides
  which row shows.
- **Highlight direction** is `_pattern_focus` (`"pattern"` or `"sample"`):
  whichever was clicked last wins.
- **Pattern strip:** one canvas drawing all 64 tiles, not 64 widgets.
- **Threads:** drive detection runs on a worker thread and hands results back
  through a `queue.Queue` polled with `root.after`. The worker never touches
  Tk.

## Constraints to remember

- **Version check:** `APP_VERSION` must match the release tag. `v5.0.1` is a
  release; `v5.0.1-rc1` is published as a pre-release.
- **Script name:** it is renamed each version (`_5_0_0.py`). The workflow
  finds it with a glob; update the README when you rename it.
- **Release notes:** put them in `.github/release-notes/<tag>.md`; without
  that file, GitHub generates notes automatically.
- **Tags from Claude Code sessions:** a session can't push tags. Releases are
  started via **Actions → Build & Release → Run workflow** (tag input), on
  `main` or any branch.
- **macOS builds use Python 3.13** to get Tcl/Tk 8.6. Tk 9 breaks tkinterdnd2
  drag & drop.
- **Download sizes:** Windows 105 MB, macOS 76/90 MB, Linux 134 MB. Windows
  and Linux use the *shared* ffmpeg builds, with each library bundled once.
  Most of the size is ffmpeg.
- **Linux users** need PortAudio from their distribution (`libportaudio2`).
- **Not verified on real hardware:** PART numbering, and sync of motion
  targets. The sample pattern files had no motion data.

## Relevant files

| File | What's there |
|---|---|
| `PyP6-Roland-P6-Sample-Manager_5_0_0.py` (32k lines) | Pattern model at ~L27840–28160: `P6Pattern`, `part_for_pad`, `find_pattern_files`, `_BLANK_PATTERN_XZ` |
| same | UI classes: `PatternStrip` (~28186), `PatternInfoCard` (~28369), `PatternDeviceDialog` (~28514), `CompactSlot.set_slim` (~27234) |
| same | App methods: `set_view_mode`, `_build_pattern_panel`, `_sync_patterns_with_pad_moves`, `swap_patterns`, `clear_selected_pattern`, `save/load_patterns_*`, `select_overview_pad` (~L30800–31700) |
| same | Undo: `_snapshot_state` / `_restore_snapshot` (~29946) |
| same | Pad-move hooks: `swap_pads`, `swap_pads_across_banks`, `swap_banks`, `transfer_bank` |
| same | Presets: `PRESET_FORMAT_VERSION` (~3491), `verify_preset_folder`, `Preset*Dialog`, `save/load_preset_to/from_folder` |
| same | Drive detection: `_mount_globs`, `_import_dirs_under`, `guess_p6_pattern_dir` (~776–900) |
| `.github/workflows/release.yml` | Build, ffmpeg check inside the frozen bundle, smoke test, publish |
| `.github/release-notes/v5.0.0.md` | Template for future release notes |
| `README.md` | §5.6 patterns, §5.10 presets, §7 releases |

## Test approach used

There are no repo tests. Checks used were `pyflakes` and `actionlint`, plus
headless runs under Xvfb that load the script with
`runpy.run_path(..., run_name="x")` and drive `P6ManagerApp` directly. A fake
mounted P-6 drive was simulated with `PYP6_MOUNT_ROOTS` pointing at a temp
folder containing `P-6/BACKUP` and `P-6/RESTORE`.

## Open items

- Test on the device: PART numbering, and that Clear then restore empties the
  slot on the P-6.
- Delete the `v4.2.3-test1` and `v4.2.3-test2` pre-releases and tags if they
  are no longer needed.
