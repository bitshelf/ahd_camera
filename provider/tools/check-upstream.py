#!/usr/bin/env python3
"""Check the exact AOSP source revision used by the versioned compatibility layer."""
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("interfaces", type=Path, help="path to AOSP hardware/interfaces")
args = parser.parse_args()
manifest = json.loads((Path(__file__).parents[1] / "compat/android15/UPSTREAM.json").read_text())
changed = []
for name, expected in manifest["files"].items():
    source = args.interfaces / name
    if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != expected:
        changed.append(name)
if changed:
    raise SystemExit("Review upstream changes before rebasing the AIDL adapter:\n" + "\n".join(changed))
print("Pinned upstream camera adapter sources match; no SDK HAL source was modified.")
