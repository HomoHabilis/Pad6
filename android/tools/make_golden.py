#!/usr/bin/env python3
"""Writes reference results from the desktop app for the Kotlin core's tests.

Loads the desktop script (it needs Tk, so run it under Xvfb) and records what
its own functions return for a set of synthetic inputs: tempo detection, hit
detection, onsets, time stretching, wavetable builds, pattern notes and
pattern edits. The Kotlin port is tested against these files, so the two
implementations cannot quietly drift apart.

    xvfb-run -a python3 android/tools/make_golden.py

Needs numpy, soundfile, pydub and sounddevice in the Python it runs with.
"""
import json
import math
import os
import runpy
import sys

import numpy as np
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
OUT = os.path.join(HERE, "..", "core", "src", "test", "resources", "golden")
os.makedirs(OUT, exist_ok=True)

g = runpy.run_path(os.path.join(ROOT, "Pad6.py"), run_name="golden")
SR = 44100


def drum_loop(bpm, beats, seed, swing=0.0, rate=SR):
    """Kick on the beats, hats on the eighths, snare on 2 and 4."""
    rng = np.random.default_rng(seed)
    n = int(round(beats * 60.0 / bpm * rate))
    x = np.zeros(n)
    beat = 60.0 / bpm * rate
    t = np.arange(int(0.25 * rate)) / rate
    kick = np.sin(2 * np.pi * (50 + 80 * np.exp(-t * 40)) * t) * np.exp(-t * 12)
    snare = rng.standard_normal(len(t)) * np.exp(-t * 25) * 0.6
    hat = rng.standard_normal(int(0.04 * rate)) * np.exp(-np.arange(int(0.04 * rate)) / rate * 120) * 0.3
    for b in range(beats):
        s = int(b * beat)
        e = min(n, s + len(kick))
        x[s:e] += kick[:e - s]
        if b % 2 == 1:
            x[s:e] += snare[:e - s]
        for half in (0, 1):
            hs = int(s + half * beat / 2 + (swing * beat / 2 if half else 0))
            he = min(n, hs + len(hat))
            if hs < n:
                x[hs:he] += hat[:he - hs]
    return (x / np.max(np.abs(x)) * 0.9).astype(np.float32)


def write_wav(name, data, rate=SR):
    path = os.path.join(OUT, name)
    sf.write(path, data, rate, subtype="PCM_16")
    # Read back what the file really holds, so both sides start from the
    # same quantised samples.
    return sf.read(path, dtype="float32")[0]


results = {}

# --- tempo, hits, onsets -------------------------------------------------
loops = [("loop120", 120, 8, 1), ("loop140", 140, 8, 2), ("loop90", 90, 4, 3), ("loop174", 174, 16, 4)]
for name, bpm, beats, seed in loops:
    data = write_wav(name + ".wav", drum_loop(bpm, beats, seed))
    bpm_found = g["detect_bpm"](data, SR)
    results[name] = {
        "bpm": bpm_found,
        "octave": g["bpm_octave_alternative"](data, SR, bpm_found),
        "hits": [int(v) for v in g["detect_hits"](data, SR, 1.0)],
        "hits18": [int(v) for v in g["detect_hits"](data, SR, 1.8)],
        "onsets": [int(v) for v in g["detect_onsets"](data, SR, 1.8)],
    }

# A sustained tone has no tempo.
tone = write_wav("tone.wav", (0.5 * np.sin(2 * np.pi * 220 * np.arange(2 * SR) / SR)).astype(np.float32))
results["tone"] = {"bpm": g["detect_bpm"](tone, SR)}

# --- time stretch ----------------------------------------------------------
data = sf.read(os.path.join(OUT, "loop120.wav"), dtype="float32")[0]
onsets = g["detect_onsets"](data, SR, 1.8)
voc = g["time_stretch"](data, SR, 0.8, transients=onsets)
voc.astype("<f4").tofile(os.path.join(OUT, "stretch_vocoder_080.f32"))
hits = g["detect_hits"](data, SR, 1.8)
sl = g["time_stretch_slices"](data, SR, 0.8, hits)
np.asarray(sl, dtype="<f4").tofile(os.path.join(OUT, "stretch_slices_080.f32"))

# --- wavetables -------------------------------------------------------------
wt = {}
cases = [
    ("basic_bass", g["WT_SIMPLE_FAMILIES"], 36, 1, 0),
    ("basic_mid", g["WT_SIMPLE_FAMILIES"], 48, 2, 12),
    ("all_basic_lead", [n for n, _f in g["WT_FAMILIES"][:19]], 50, 2, 24),
    ("vintage1", [f"VC1 - No {k:02d}" for k in range(1, 17)], 36, 1, 0),
    ("hotmw2", [f"Hot MW 2 SWP {k:02d}" for k in range(1, 17)], 43, 1, 5),
    ("big", ["Hot MW 1 Big 255WF"], 36, 1, 0),
]
for name, sel, midi, cycles, up in cases:
    pcm, rows, meta = g["wt_build"](sel, midi, cycles, up)
    pcm.astype("<i2").tofile(os.path.join(OUT, f"wt_{name}.i16"))
    wt[name] = {"families": list(sel), "midi": midi, "cycles": cycles, "up": up,
                "meta": meta, "desc": [r[3] for r in rows]}
results["wavetables"] = wt

# --- patterns -----------------------------------------------------------------
P6Pattern = g["P6Pattern"]
blank = g["blank_pattern_text"]()


def with_steps(text, steps):
    """Puts notes into a blank pattern. steps: {step: [(part, note, velo, leng, sub, prob, mt)]}"""
    lines = text.splitlines(keepends=True)
    out = []
    for line in lines:
        key = line.split("\t")[0]
        if key.startswith("STEP_NOTE_SMPL "):
            s = int(key.split()[-1])
            if s in steps:
                fields = []
                for i, (part, note, velo, leng, sub, prob, mt) in enumerate(steps[s], 1):
                    fields.append(f"PART{i}={part} NOTE{i}={note} VELO{i}={velo} LENG{i}={leng} "
                                  f"SUB{i}={sub} PROB{i}={prob} MT{i}={mt}")
                line = f"{key}\t= " + "       ".join(fields) + "       \n"
        out.append(line)
    return "".join(out)


steps = {
    1: [(0, 60, 100, 80, 0, 10, 0), (6, 60, 90, 100, 0, 10, 0)],
    2: [(6, 60, 90, 100, 0, 10, 0)],
    3: [(6, 60, 90, 40, 0, 10, 0)],
    5: [(0, 62, 110, 255, 0, 10, -20), (13, 60, 80, 80, 2, 10, 0)],
    6: [(0, 62, 110, 50, 0, 10, 0)],
    9: [(47, 64, 127, 80, 0, 10, 10)],
    16: [(1, 60, 100, 100, 0, 10, 0)],
    1000: [],
}
text = with_steps(blank, steps)
text = text.replace("LENG\t= 32", "LENG\t= 16")
with open(os.path.join(OUT, "pattern_a.prm"), "w", newline="") as f:
    f.write(text)
pat = P6Pattern(text)
notes = g["pattern_step_notes"](pat)
remapped = pat.remap_parts({0: 6, 6: 0, 47: 12})
results["pattern"] = {
    "length": pat.length,
    "tempo": pat.tempo,
    "note_count": pat.note_count,
    "used_pads": sorted(f"{b}{p}" for b, p in pat.used_pads()),
    "notes": [{k: n[k] for k in ("step", "part", "note", "velo", "sub", "prob", "mt", "steps")} for n in notes],
    "cleared_equals_blank_steps": pat.cleared().text == with_steps(blank, {}).replace("LENG\t= 32", "LENG\t= 16"),
    "remapped_used_pads": sorted(f"{b}{p}" for b, p in remapped.used_pads()),
}
with open(os.path.join(OUT, "pattern_a_remapped.prm"), "w", newline="") as f:
    f.write(remapped.text)
with open(os.path.join(OUT, "pattern_a_cleared.prm"), "w", newline="") as f:
    f.write(pat.cleared().text)

# --- PRM ---------------------------------------------------------------------
results["prm"] = {
    "render_B3_acid": g["render_prm"]("B", 3, 0, 674, 171870, template="Acid", poly=True),
    "render_H6_init": g["render_prm"]("H", 6, 0, 1020, 260100),
}

# --- names -------------------------------------------------------------------
results["safe_names"] = {n: g["safe_base_name"](n, strip_tags=True) for n in [
    "Straße Kick.wav", "aux.wav", ".hidden.wav", "a:b*c?.wav", "Kick_trim_a1b2c3d4.wav",
    "Ångström bass.WAV", "x" * 80 + ".wav", "Snare_imp_A1_0123abcd_norm_89abcdef.wav",
]}

with open(os.path.join(OUT, "golden.json"), "w") as f:
    json.dump(results, f, indent=1, default=float)
print("wrote", OUT)
