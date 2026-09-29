#!/usr/bin/env python3
"""Extracts the data blobs embedded in the desktop script into plain resource
files for the Android core module.

The desktop app keeps its wavetable collections and the P-6's blank pattern
as base64 + LZMA string literals so it can stay a single file. The Android
build has no LZMA decoder in the JDK, so the blobs are unpacked once here and
committed as raw files (int16 little-endian for the waveforms, text for the
rest). Re-run after the desktop script changes any of them:

    python3 android/tools/extract_resources.py
"""
import base64
import lzma
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "..", "PyP6-Roland-P6-Files-Manager.py")
OUT = os.path.join(HERE, "..", "core", "src", "main", "resources", "pyp6")

BLOBS = {
    "VINTAGE_WT_DATA": "vintage_wt.i16",
    "HOT_MW_WT_DATA": "hot_mw_wt.i16",
    "HOT_MW_BIG_DATA": "hot_mw_big.i16",
    "HOT_MW_BIG_LABELS": "hot_mw_big_labels.txt",
    "_BLANK_PATTERN_XZ": "blank_pattern.prm",
}


def blob(source, name):
    m = re.search(rf"^{re.escape(name)} = \(\n(.*?)^\)", source, re.S | re.M)
    if not m:
        raise SystemExit(f"{name} not found in the desktop script")
    parts = re.findall(r'"([^"]*)"', m.group(1))
    return lzma.decompress(base64.b64decode("".join(parts)))


def main():
    with open(SCRIPT, encoding="utf-8") as f:
        source = f.read()
    os.makedirs(OUT, exist_ok=True)
    for name, filename in BLOBS.items():
        data = blob(source, name)
        with open(os.path.join(OUT, filename), "wb") as f:
            f.write(data)
        print(f"{filename}: {len(data)} bytes")
    write_rng_tables()


def write_rng_tables():
    """Random phases two built-in families draw from numpy's default_rng.

    Bell / Metal seeds it with 77 and draws one value per partial, Noise
    Morph seeds it with 2024 and draws one per harmonic. Stored as values so
    the Kotlin port produces exactly the same tables without reimplementing
    numpy's PCG64 and seed sequence. Needs numpy.
    """
    try:
        import numpy as np
    except ImportError:
        print("numpy not installed - rng tables left as they are")
        return
    noise = np.random.default_rng(2024).random(2048)
    with open(os.path.join(OUT, "rng_noise_2024.txt"), "w") as f:
        f.write("\n".join(repr(float(v)) for v in noise) + "\n")
    g = np.random.default_rng(77)
    bell = [g.random() for _ in range(9)]
    with open(os.path.join(OUT, "rng_bell_77.txt"), "w") as f:
        f.write("\n".join(repr(float(v)) for v in bell) + "\n")


if __name__ == "__main__":
    main()
