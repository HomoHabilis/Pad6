#!/usr/bin/env python3
"""Exports Pad6 wavetables for the Arturia MiniFreak (firmware 5.0+).

The MiniFreak's wavetable import takes mono, 24-bit WAV files built from
512-point single-cycle frames - the layout Pigments and Serum use. This
script renders those from the same family engine Pad6 uses for the P-6, so
every built-in family, every collection and every shape in your own library
is available, but it writes only to the folder you name and never touches a
P-6 project, a pad or ~/.pyp6.

It is a separate file on purpose. The P-6 table is 255 segments tuned to a
root note and band limited for the P-6's transpose range; a MiniFreak table
is a stack of pitch-free 512-point cycles that the synth band limits itself.
Different enough that bending wt_build() to do both would mean threading
MiniFreak cases through code that exists for the P-6.

Same logic as the Synth dialog: pick families, and the frames are shared
out between them evenly (wt_split_steps), each family sweeping its morph
from 0 to 1 across its share. The only difference is the total - any frame
count from 1 to 512 instead of the P-6's fixed 255.

    python3 minifreak_export.py list
    python3 minifreak_export.py sets                    # every set, one file each
    python3 minifreak_export.py sets "Vintage Collection 1" --frames 64
    python3 minifreak_export.py build Saw FM "Wave Folder" --name MyTable
    python3 minifreak_export.py each --set Basic --frames 32
    python3 minifreak_export.py p6                      # tables already on pads

Needs the same Python environment as Pad6.py (numpy, tkinter, sounddevice,
soundfile), since the engine is imported from it. No window is opened.

The Synth dialog's "Export for MiniFreak..." button uses this module too,
which is why it takes the engine from the running app when there is one.
"""
import argparse
import importlib
import os
import re
import struct
import sys
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)


def _engine():
    """Pad6's engine module.

    Inside the app, Pad6 is __main__, and `import Pad6` would run all of
    it a second time under another name - another 178 families decoded,
    and a second WT_FAMILY_GROUPS the dialog never sees. So the running
    app is used when it is the one asking. From the command line it is
    imported normally; that builds no UI. importlib rather than an import
    statement so PyInstaller, which bundles this file with the app, does
    not pack a second copy of Pad6 for a path the app never takes.
    """
    main = sys.modules.get("__main__")
    if main is not None and hasattr(main, "WTSynth") and hasattr(main, "wt_family_entry"):
        return main
    return importlib.import_module("Pad6")


p6 = _engine()


MF_FRAME = 512          # points per cycle, fixed by the MiniFreak format
MF_MAX_FRAMES = 512
# negligible-mass/minifreak-converter reports MiniFreak V crashing on
# tables longer than this. The limit is not published by Arturia and was
# seen on the .raw route, but nothing is gained by finding out on the
# synth, so it is the default for every export, and going past it is
# allowed with a warning.
MF_SAFE_FRAMES = 189
MF_DEFAULT_FRAMES = MF_SAFE_FRAMES
# The rate written into the header. A wavetable has no real sample rate -
# each frame is one cycle whatever the pitch - but 48 kHz is the
# MiniFreak's own and what its importer expects to see.
MF_SR = 48000
# Every harmonic a 512-point cycle can hold, short of Nyquist itself
# (WTSynth.band_limit zeroes that bin anyway). No register, no transpose
# range: the MiniFreak band limits its tables per note on playback, so
# dropping harmonics here would only make the table duller.
MF_HARMONICS = MF_FRAME // 2 - 1
# Families are rendered at this multiple of the frame and then reduced to
# 512 points in the frequency domain. Additive families come out the same
# either way, but FM, Hard Sync, Phase Dist., Wave Folder and Staircase are
# built in the time domain, and at 512 points their upper partials fold
# back before band_limit() ever sees them. Measured against a 32x render,
# the worst FM / Hard Sync / Phase Dist. frame was 44 % of full scale off at
# 1x, 4 % at 8x and 1.4 % at 16x - the last for about 11 s to write every
# set, which is a fair price for a one-off export.
MF_OVERSAMPLE = 16
# Pigments-style tables are normalised close to full scale; a little
# headroom keeps the 24-bit rounding clear of the rail.
MF_PEAK_DB = -0.3
# Formant-based families (Vowel, Piano, Strings, Brass) place their
# resonances in Hz, so they need a reference pitch even though the frame
# itself has none. C3 is the root Pad6's Mid and Lead registers use.
MF_DEFAULT_NOTE = "C3"


# ---------------------------------------------------------------------------
# What can be exported
# ---------------------------------------------------------------------------

def mf_user_library():
    """The user's own shapes: waveforms.json plus any .p6wf pack dropped
    into user_wave_families that the library does not hold yet. Read only -
    the in-memory merge is never written back."""
    lib = p6.load_drawn_library()
    try:
        p6.import_new_user_family_packs(lib)
    except Exception as e:
        print(f"Could not read waveform packs: {e}")
    return lib


def mf_sets(library=None):
    """(label, [family names]) for every set that makes sense as a table.

    Basic is the same sixteen the Synth dialog's Simple mode builds, so a
    MiniFreak "Basic" sounds like a P-6 "Basic". A collection contributes
    its WHOLE folder rather than the first sixteen: that cap exists because
    255 segments split sixteen ways is already coarse, and 512 frames are
    not short of room. Multi families stand on their own, as in Simple
    mode, and so does each of the user's folders.
    """
    library = library or {}
    out = [("Basic", list(p6.WT_SIMPLE_FAMILIES))]
    for label, names in p6.WT_FAMILY_GROUPS:
        if label == "Basic":
            continue
        plain = [n for n in names if n not in p6.WT_BUILTIN_MULTI]
        if plain:
            out.append((label, plain))
    for label, names in p6.WT_FAMILY_GROUPS:
        out += [(n, [n]) for n in names if n in p6.WT_BUILTIN_MULTI]
    groups = {}
    for name, e in library.items():
        groups.setdefault(e.get("group") or p6.WT_DEFAULT_USER_GROUP, []).append(name)
    for group in sorted(groups, key=lambda g: (g != p6.WT_DEFAULT_USER_GROUP, g.lower())):
        names = sorted(groups[group], key=p6.wt_natural_key)
        plain = [n for n in names if not p6.wt_entry_is_multi(library[n])]
        if plain:
            out.append((group, plain))
        out += [(n, [n]) for n in names if p6.wt_entry_is_multi(library[n])]
    return out


def mf_resolve(name, library):
    """A family name -> what the engine takes: a library entry, or the name
    of a built-in. Matching is case-insensitive as a convenience for typing
    names on the command line."""
    if name in library:
        return library[name]
    canon = p6.wt_canonical_family(name)
    if canon in p6.WT_FAMILY_MAP:
        return canon
    low = name.lower()
    for n in library:
        if n.lower() == low:
            return library[n]
    for n in p6.WT_FAMILY_MAP:
        if n.lower() == low:
            return n
    raise KeyError(f"no family called {name!r} (see: minifreak_export.py list)")


def _multi_entry(item):
    """The shape list behind a multi family, or None for a morphing one."""
    if isinstance(item, dict):
        return item if p6.wt_entry_is_multi(item) else None
    if item in p6.WT_BUILTIN_MULTI:
        return p6._hot_mw_big_entry()
    return None


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def mf_default_frames(selection):
    """MF_DEFAULT_FRAMES, except that a lone multi family with fewer shapes
    gets one frame per shape - more would only repeat shapes."""
    if len(selection) == 1:
        multi = _multi_entry(selection[0])
        if multi:
            return max(1, min(MF_DEFAULT_FRAMES, len(multi.get("shapes") or [])))
    return MF_DEFAULT_FRAMES


def _to_frame(w):
    """One oversampled cycle -> 512 points, by keeping the harmonics a
    512-point frame can hold. Exact for a band-limited cycle: no
    interpolation, nothing folds back."""
    spec = np.fft.rfft(w)[:MF_FRAME // 2 + 1]
    spec[-1] = 0.0
    return np.fft.irfft(spec, n=MF_FRAME)


def mf_build(selection, frames=None, note=MF_DEFAULT_NOTE, peak_db=MF_PEAK_DB,
             harmonics=MF_HARMONICS):
    """Renders `selection` (family names or library entries, in order) into
    a (frames, 512) float array plus one map row per family.

    One synth for the whole table, one cycle per frame, which is what makes
    every frame a single cycle the MiniFreak can loop. It runs oversampled
    (MF_OVERSAMPLE) and each frame is reduced to 512 points afterwards.
    """
    if not selection:
        raise ValueError("nothing selected")
    frames = int(frames or mf_default_frames(selection))
    if not 1 <= frames <= MF_MAX_FRAMES:
        raise ValueError(f"frames must be 1..{MF_MAX_FRAMES}, got {frames}")
    if frames < len(selection):
        raise ValueError(f"{len(selection)} families need at least as many "
                         f"frames, got {frames}")
    f0 = p6.midi_to_hz(p6.name_to_midi(note))
    s = p6.WTSynth(MF_FRAME * MF_OVERSAMPLE, 1,
                   max(1, min(int(harmonics), MF_HARMONICS)))
    peak = 10.0 ** (peak_db / 20.0)

    counts = p6.wt_split_steps(len(selection), frames)
    out = np.empty((frames, MF_FRAME))
    rows, i = [], 0
    for item, count in zip(selection, counts):
        name, fn = p6.wt_family_entry(item)
        multi = _multi_entry(item)
        if multi:
            # Picked directly rather than through the family's own render
            # function, which indexes against the P-6's 255 segments and
            # would sample the wrong shapes for any other frame count.
            shapes = multi.get("shapes") or []
            labels = multi.get("labels") or []
            picks = [0] if count == 1 else [
                int(round(j * (len(shapes) - 1) / (count - 1))) for j in range(count)]
            first = last = ""
            for j, k in enumerate(picks):
                out[i + j] = _to_frame(p6.wt_points_to_cycle(shapes[k], s))
                desc = labels[k] if k < len(labels) else str(k + 1)
                first = first or desc
                last = desc
        else:
            first = last = ""
            for j in range(count):
                m = 0.5 if count == 1 else j / (count - 1)
                w, desc = fn(s, m, f0)
                out[i + j] = _to_frame(s.band_limit(np.asarray(w, dtype=np.float64)))
                first = first or desc
                last = desc
        rows.append((i, i + count - 1, name, first, last))
        i += count

    # Per frame, as wt_build() does: scanning the table should change the
    # timbre, not the level. DC is already gone (band_limit drops bin 0).
    pk = np.max(np.abs(out), axis=1, keepdims=True)
    out = np.where(pk > 1e-12, out / np.where(pk > 1e-12, pk, 1.0), out) * peak
    return out, rows


# ---------------------------------------------------------------------------
# Tables already built for the P-6
# ---------------------------------------------------------------------------

def mf_from_p6_wavetable(path, frames=None, peak_db=MF_PEAK_DB):
    """Recovers the cycles from a wavetable Pad6 built for a P-6 pad.

    Such a file is 255 segments of L frames, each holding R whole cycles
    (R = 1 on Bass, 2 on Mid and Lead). Harmonic k of a segment sits on FFT
    bin k*R, so reading those bins gives the cycle exactly, at whatever
    resolution is wanted - no resampling guesswork. R is detected rather
    than asked for: with R = 2 every odd bin is empty.

    The result carries the P-6 band limit the table was built with. For a
    full-bandwidth MiniFreak version, rebuild it from its families instead.
    Segments left silent (a multi family with fewer than 255 shapes) are
    dropped.
    """
    with wave.open(path, "rb") as f:
        if f.getnchannels() != 1 or f.getsampwidth() != 2:
            raise ValueError("not a Pad6 wavetable (expected mono 16-bit)")
        data = np.frombuffer(f.readframes(f.getnframes()), dtype="<i2")
    if data.size == 0 or data.size % p6.WT_SEGMENTS:
        raise ValueError(f"{data.size} frames is not {p6.WT_SEGMENTS} equal segments")
    L = data.size // p6.WT_SEGMENTS
    segs = data.reshape(p6.WT_SEGMENTS, L).astype(np.float64) / 32767.0
    spec = np.fft.rfft(segs, axis=1)
    power = np.sum(np.abs(spec[:, 1:]) ** 2, axis=0)
    odd = power[0::2].sum()             # bins 1, 3, 5 ...
    R = 2 if odd < 1e-6 * max(power.sum(), 1e-30) else 1

    h = min(MF_HARMONICS, (spec.shape[1] - 1) // R)
    bins = np.zeros((p6.WT_SEGMENTS, MF_FRAME // 2 + 1), dtype=complex)
    bins[:, 1:h + 1] = spec[:, R:R * h + 1:R]
    cycles = np.fft.irfft(bins, n=MF_FRAME, axis=1)
    pk = np.max(np.abs(cycles), axis=1)
    cycles = cycles[pk > 1e-6]
    pk = pk[pk > 1e-6]
    if not len(cycles):
        raise ValueError("the table is silent")
    cycles = cycles / pk[:, None] * 10.0 ** (peak_db / 20.0)
    if frames and frames < len(cycles):
        idx = np.round(np.linspace(0, len(cycles) - 1, int(frames))).astype(int)
        cycles = cycles[idx]
    return cycles, dict(L=L, R=R, harmonics=h)


# ---------------------------------------------------------------------------
# Writing
# ---------------------------------------------------------------------------

def _int24(table):
    q = np.clip(np.round(np.asarray(table, dtype=np.float64).ravel() * 8388608.0),
                -8388608, 8388607).astype("<i4")
    return q.view(np.uint8).reshape(-1, 4)[:, :3].tobytes()


def write_mf_wav(path, table):
    """Mono 24-bit WAV with a Serum-style `clm ` chunk declaring the 512
    point frame - the chunk Pigments, Serum, Vital and most converters read
    to find the frame size, so the file is a proper wavetable for all of
    them, not only the MiniFreak."""
    pcm = _int24(table)
    clm = b"<!>%d 00000000 wavetable Pad6" % MF_FRAME
    clm += b"\0" * (len(clm) % 2)
    fmt = struct.pack("<HHIIHH", 1, 1, MF_SR, MF_SR * 3, 3, 24)
    body = (b"WAVE"
            + b"fmt " + struct.pack("<I", len(fmt)) + fmt
            + b"clm " + struct.pack("<I", len(clm)) + clm
            + b"data" + struct.pack("<I", len(pcm)) + pcm
            + (b"\0" if len(pcm) % 2 else b""))
    with open(path, "wb") as f:
        f.write(b"RIFF" + struct.pack("<I", len(body)) + body)


def write_mf_raw(path, table):
    """Headerless 24-bit little-endian frames: the .raw MiniFreak V keeps in
    its own WT folder, for dropping in directly instead of importing."""
    with open(path, "wb") as f:
        f.write(_int24(table))


def write_mf_map(path, rows, frames, note):
    """Which frames hold which family - the MiniFreak shows a position, not
    a family name, so this is the sheet to keep beside it."""
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"{p6.APP_NAME} MiniFreak wavetable - {frames} frames x {MF_FRAME} points\n")
        f.write(f"Formant reference pitch: {note}\n\n")
        f.write("frames      position  family                     starts as -> ends as\n")
        for lo, hi, name, d0, d1 in rows:
            pos = f"{lo / max(1, frames - 1) * 100:5.1f}%" if frames > 1 else "  0.0%"
            span = d0 if d0 == d1 else f"{d0} -> {d1}"
            f.write(f"{lo:3d}-{hi:<3d}     {pos}    {name:<26} {span}\n")


def mf_export(selection, wav_path, frames=None, note=MF_DEFAULT_NOTE,
              write_map=False):
    """Builds one table and writes it to `wav_path`, with its .txt frame map
    beside it when asked. What the Synth dialog's export button calls.
    Returns (table, rows)."""
    table, rows = mf_build(selection, frames, note)
    write_mf_wav(wav_path, table)
    if write_map:
        write_mf_map(os.path.splitext(wav_path)[0] + ".txt", rows, len(table), note)
    return table, rows


def mf_file_name(label):
    """A short FAT/Windows-safe name: the files are copied around by hand
    and MiniFreak V shows the name in a narrow browser."""
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]+', "-", label)
    name = re.sub(r"\s+", " ", name).strip(" .-")
    return (name or "Wavetable")[:40]


def mf_save(table, out_dir, label, fmt="wav", rows=None, note=MF_DEFAULT_NOTE):
    os.makedirs(out_dir, exist_ok=True)
    base = os.path.join(out_dir, mf_file_name(label))
    written = []
    if fmt in ("wav", "both"):
        write_mf_wav(base + ".wav", table)
        written.append(base + ".wav")
    if len(table) > MF_SAFE_FRAMES:
        print(f"  warning: {len(table)} frames - MiniFreak V is reported to "
              f"crash on tables above {MF_SAFE_FRAMES}")
    if fmt in ("raw", "both"):
        write_mf_raw(base + ".raw", table)
        written.append(base + ".raw")
    if rows is not None:
        write_mf_map(base + ".txt", rows, len(table), note)
    return written


# ---------------------------------------------------------------------------
# Command line
# ---------------------------------------------------------------------------

def _report(paths, table, rows=None):
    for p in paths:
        print(f"  {p}  ({len(table)} frames)")
    if rows and len(rows) > 1:
        per = sorted({hi - lo + 1 for lo, hi, *_ in rows})
        print(f"    {len(rows)} families, {per[0]}"
              + (f"-{per[-1]}" if len(per) > 1 else "") + " frames each")


def cmd_list(args):
    lib = mf_user_library()
    for label, names in mf_sets(lib):
        print(f"[{label}]  {len(names)} famil{'y' if len(names) == 1 else 'ies'}")
        if args.verbose:
            for n in names:
                print(f"    {n}")
    if not args.verbose:
        print("\n(-v lists the families in each set)")


def cmd_sets(args):
    lib = mf_user_library()
    sets = mf_sets(lib)
    if args.set:
        by_low = {lbl.lower(): (lbl, names) for lbl, names in sets}
        try:
            sets = [by_low[s.lower()] for s in args.set]
        except KeyError as e:
            raise SystemExit(f"no set called {e.args[0]!r} (see: minifreak_export.py list)")
    for label, names in sets:
        sel = [mf_resolve(n, lib) for n in names]
        frames = args.frames or mf_default_frames(sel)
        if frames < len(sel):
            print(f"{label}: {len(sel)} families but only {frames} frames - "
                  f"using {len(sel)}")
            frames = len(sel)
        table, rows = mf_build(sel, frames, args.note, args.peak)
        _report(mf_save(table, args.out, f"{args.prefix}{label}", args.format,
                        rows if args.map else None, args.note), table, rows)


def cmd_build(args):
    lib = mf_user_library()
    sel = [mf_resolve(n, lib) for n in args.families]
    table, rows = mf_build(sel, args.frames, args.note, args.peak)
    label = args.name or "-".join(p6.wt_selection_names(sel))
    _report(mf_save(table, args.out, f"{args.prefix}{label}", args.format,
                    rows if args.map else None, args.note), table, rows)


def cmd_each(args):
    """One table per family - the morph of a single family spread over all
    its frames, the finest-grained version of each."""
    lib = mf_user_library()
    sets = dict(mf_sets(lib))
    if args.set:
        names = []
        for s in args.set:
            match = next((v for k, v in sets.items() if k.lower() == s.lower()), None)
            if match is None:
                raise SystemExit(f"no set called {s!r} (see: minifreak_export.py list)")
            names += match
    else:
        names = list(dict.fromkeys(n for v in sets.values() for n in v))
    for n in names:
        sel = [mf_resolve(n, lib)]
        table, rows = mf_build(sel, args.frames, args.note, args.peak)
        _report(mf_save(table, args.out, f"{args.prefix}{n}", args.format,
                        rows if args.map else None, args.note), table)


def cmd_p6(args):
    paths = args.files
    if not paths:
        try:
            paths = sorted(os.path.join(p6.WAVETABLE_DIR, f)
                           for f in os.listdir(p6.WAVETABLE_DIR)
                           if f.lower().endswith(".wav"))
        except OSError:
            paths = []
        if not paths:
            raise SystemExit(f"no wavetables in {p6.WAVETABLE_DIR}")
    for path in paths:
        try:
            table, info = mf_from_p6_wavetable(path, args.frames, args.peak)
        except Exception as e:
            print(f"skipped {path}: {e}")
            continue
        label = os.path.splitext(os.path.basename(path))[0]
        print(f"{path}: {info['R']} cycle(s) per segment, "
              f"{info['harmonics']} harmonics kept")
        _report(mf_save(table, args.out, f"{args.prefix}{label}", args.format),
                table)


def main(argv=None):
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-o", "--out", default="minifreak_wavetables",
                        help="output folder (default: %(default)s)")
    common.add_argument("--frames", type=int, default=None,
                        help=f"frames per table, 1-{MF_MAX_FRAMES} (default "
                             f"{MF_DEFAULT_FRAMES}, the largest reported safe; "
                             f"a lone multi family with fewer shapes gets one "
                             f"per shape)")
    common.add_argument("--format", choices=("wav", "raw", "both"), default="wav",
                        help="wav for the MiniFreak's Import, raw for MiniFreak "
                             "V's WT folder (default: %(default)s)")
    common.add_argument("--note", default=MF_DEFAULT_NOTE,
                        help="reference pitch for the formant families "
                             "(default: %(default)s)")
    common.add_argument("--peak", type=float, default=MF_PEAK_DB,
                        help="per-frame peak in dBFS (default: %(default)s)")
    common.add_argument("--prefix", default="",
                        help="prepended to every file name")
    common.add_argument("--map", action="store_true",
                        help="also write a .txt listing which frames hold which family")

    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("list", help="show the sets and families available")
    p.add_argument("-v", "--verbose", action="store_true")
    p.set_defaults(fn=cmd_list)
    p = sub.add_parser("sets", parents=[common],
                       help="one table per set (all sets unless named)")
    p.add_argument("set", nargs="*")
    p.set_defaults(fn=cmd_sets)
    p = sub.add_parser("build", parents=[common],
                       help="one table from families you name, in order")
    p.add_argument("families", nargs="+")
    p.add_argument("--name", help="file name (default: the family names)")
    p.set_defaults(fn=cmd_build)
    p = sub.add_parser("each", parents=[common],
                       help="one table per family")
    p.add_argument("--set", action="append",
                   help="only the families of this set (repeatable)")
    p.set_defaults(fn=cmd_each)
    p = sub.add_parser("p6", parents=[common],
                       help=f"convert tables Pad6 built for pads (default: all "
                            f"in {p6.WAVETABLE_DIR})")
    p.add_argument("files", nargs="*")
    p.set_defaults(fn=cmd_p6)

    args = ap.parse_args(argv)
    try:
        if args.cmd != "list":
            p6.name_to_midi(args.note)
        args.fn(args)
    except (KeyError, ValueError) as e:
        raise SystemExit(f"error: {e.args[0] if e.args else e}")


if __name__ == "__main__":
    main()
