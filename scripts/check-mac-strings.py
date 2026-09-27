#!/usr/bin/env python3
"""Pastikan setiap teks UI app Mac punya terjemahan Indonesia (mac-agent/Resources/id.lproj/Localizable.strings).

Teks Inggris di kode adalah key-nya. Skrip ini mengambil literal dari Text/Button/Toggle/Label/LocalizedStringKey,
.help/.accessibilityLabel, String(localized:), dan step(n, "…"), lalu mengubah interpolasi Swift menjadi %@,
sama seperti yang dilakukan SwiftUI untuk nilai String.
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = ROOT / "mac-agent" / "Sources" / "ArmrestAgent"
STRINGS = ROOT / "mac-agent" / "Resources" / "id.lproj" / "Localizable.strings"
START = re.compile(
    r'\b(?:Text|Button|Toggle|Label|LocalizedStringKey)\(\s*"'
    r'|\.(?:help|accessibilityLabel)\(\s*"'
    r'|\bString\(localized:\s*"'
    r'|\bstep\(\d+,\s*"'
)
ESCAPES = {'"': '"', "\\": "\\", "n": "\n", "t": "\t", "'": "'"}


def literal(source: str, start: int) -> str:
    """Baca string literal Swift mulai tepat setelah tanda kutip pembuka."""
    out, i = [], start
    while True:
        c = source[i]
        if c == '"':
            return "".join(out)
        if c == "\\":
            nxt = source[i + 1]
            if nxt == "(":
                depth, i = 1, i + 2
                while depth:
                    depth += {"(": 1, ")": -1}.get(source[i], 0)
                    i += 1
                out.append("%@")
                continue
            out.append(ESCAPES.get(nxt, nxt))
            i += 2
            continue
        out.append(c)
        i += 1


def main() -> int:
    for path in (STRINGS, STRINGS.parent.parent / "en.lproj" / "Localizable.strings"):
        subprocess.run(["plutil", "-lint", "-s", str(path)], check=True)
    translations = json.loads(subprocess.run(
        ["plutil", "-convert", "json", "-o", "-", str(STRINGS)], check=True, capture_output=True, text=True,
    ).stdout)
    used = {}
    for path in sorted(SOURCES.glob("*.swift")):
        source = path.read_text()
        for match in START.finditer(source):
            key = literal(source, match.end())
            used.setdefault(key, f"{path.name}:{source.count(chr(10), 0, match.start()) + 1}")
    missing = {key: where for key, where in used.items() if key not in translations}
    for key, where in missing.items():
        print(f"{where}: belum ada terjemahan: {key!r}")
    for key in sorted(set(translations) - set(used)):
        print(f"peringatan: terjemahan tidak dipakai: {key!r}")
    print(f"{len(used)} teks dicek, {len(missing)} belum diterjemahkan.")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
