#!/usr/bin/env python3
"""Keyboard geometry: load/save the KeyboardGeometry JSON used by the engine and
synthesise a default Pixel 10 Pro portrait QWERTY layout.

JSON contract (see DESIGN.md, "Engine API" / "Keyboard geometry"):

    {"id": "...", "width": 1080, "height": 790,
     "keys": [{"code": 113, "label": "q", "x": 0, "y": 0, "w": 108, "h": 165}, ...]}

`x`/`y` are the top-left corner of the key in pixels, `w`/`h` its size. Letter codes
are the Unicode code points of the lowercase letter. Special keys use negative codes.

CLI:
    geometry.py generate [--out eval/data/geometry/pixel10pro_qwerty.json] [--force]
    geometry.py show <geometry.json>        # ASCII dump of the layout
    geometry.py resolve <geometry.json> X Y # which key a tap at (X, Y) resolves to
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Iterator

# Special key codes used in the synthetic geometry. The real geometry is exported by the
# app and is the source of truth; consumers must read codes from the JSON, not from here.
CODE_SHIFT = -1
CODE_SYMBOLS = -3
CODE_DELETE = -7
CODE_ENTER = 10
CODE_SPACE = 32
CODE_COMMA = 44
CODE_PERIOD = 46
CODE_APOSTROPHE = 39

DEFAULT_GEOMETRY_PATH = Path(__file__).resolve().parent / "data" / "geometry" / "pixel10pro_qwerty.json"


@dataclass(frozen=True)
class Key:
    code: int
    label: str
    x: float
    y: float
    w: float
    h: float

    @property
    def cx(self) -> float:
        return self.x + self.w / 2.0

    @property
    def cy(self) -> float:
        return self.y + self.h / 2.0

    def contains(self, px: float, py: float) -> bool:
        return self.x <= px < self.x + self.w and self.y <= py < self.y + self.h

    def is_letter(self) -> bool:
        return 0 < self.code < 0x110000 and chr(self.code).isalpha()


@dataclass
class Geometry:
    id: str
    width: float
    height: float
    keys: list[Key] = field(default_factory=list)

    # ---- construction / IO -------------------------------------------------------
    @classmethod
    def from_dict(cls, d: dict) -> "Geometry":
        keys = [Key(int(k["code"]), str(k["label"]), float(k["x"]), float(k["y"]),
                    float(k["w"]), float(k["h"])) for k in d["keys"]]
        return cls(str(d["id"]), float(d["width"]), float(d["height"]), keys)

    @classmethod
    def load(cls, path: str | Path) -> "Geometry":
        with open(path, encoding="utf-8") as f:
            return cls.from_dict(json.load(f))

    def to_dict(self) -> dict:
        return {"id": self.id, "width": self.width, "height": self.height,
                "keys": [asdict(k) for k in self.keys]}

    def save(self, path: str | Path) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, indent=1)
            f.write("\n")

    # ---- lookups -----------------------------------------------------------------
    def key_for_code(self, code: int) -> Key | None:
        for k in self.keys:
            if k.code == code:
                return k
        return None

    def key_for_char(self, ch: str) -> Key | None:
        """Letter lookup is case-insensitive (shift is a separate key)."""
        k = self.key_for_code(ord(ch.lower()))
        return k if k is not None else self.key_for_code(ord(ch))

    def key_at(self, px: float, py: float) -> Key | None:
        """Key whose rect contains the point, or None if the point is in a gap."""
        for k in self.keys:
            if k.contains(px, py):
                return k
        return None

    def resolve(self, px: float, py: float) -> Key:
        """What the keyboard resolves a tap to: the containing key, else the nearest key
        by centre distance (HeliBoard's hit boxes cover the gaps between keys)."""
        k = self.key_at(px, py)
        if k is not None:
            return k
        return min(self.keys, key=lambda kk: (kk.cx - px) ** 2 + (kk.cy - py) ** 2)

    def letter_keys(self) -> list[Key]:
        return [k for k in self.keys if k.is_letter()]

    def neighbours(self, key: Key, radius_keys: float = 1.95) -> list[Key]:
        """Letter keys whose centre is within `radius_keys` key-widths of `key`'s centre
        (excluding `key` itself). On the Pixel layout (row pitch 1.83 key widths, half-key
        stagger) this is the 6-8 surrounding keys, but not the key two to the left/right."""
        r = radius_keys * key.w
        out = []
        for k in self.letter_keys():
            if k is key or k.code == key.code:
                continue
            if math.hypot(k.cx - key.cx, k.cy - key.cy) <= r:
                out.append(k)
        return out

    def mean_letter_size(self) -> tuple[float, float]:
        ks = self.letter_keys()
        return (sum(k.w for k in ks) / len(ks), sum(k.h for k in ks) / len(ks))

    def __iter__(self) -> Iterator[Key]:
        return iter(self.keys)


def default_pixel10pro_qwerty() -> Geometry:
    """A realistic portrait QWERTY matching the Pixel 10 Pro (1080 px wide screen).

    4 rows (3 letter rows + bottom row), row pitch 197.5 px, letter keys 108 x 165 px with
    a 32.5 px vertical gap. Row 2 is offset by half a key, row 3 starts after a shift key
    1.15 keys wide and ends with the delete key. Bottom row: ?123, comma, space, period,
    enter.
    """
    width = 1080.0
    kw, kh = 108.0, 165.0
    pitch = 197.5
    height = 790.0
    keys: list[Key] = []

    def row(y: float, letters: str, x0: float) -> None:
        for i, ch in enumerate(letters):
            keys.append(Key(ord(ch), ch, x0 + i * kw, y, kw, kh))

    row(0.0, "qwertyuiop", 0.0)
    row(pitch, "asdfghjkl", kw / 2.0)
    y3 = 2 * pitch
    shift_w = 1.15 * kw
    keys.append(Key(CODE_SHIFT, "shift", 0.0, y3, shift_w, kh))
    row(y3, "zxcvbnm", shift_w)
    del_x = shift_w + 7 * kw
    keys.append(Key(CODE_DELETE, "delete", del_x, y3, width - del_x, kh))
    y4 = 3 * pitch
    sym_w = 1.5 * kw
    x = 0.0
    keys.append(Key(CODE_SYMBOLS, "?123", x, y4, sym_w, kh)); x += sym_w
    keys.append(Key(CODE_COMMA, ",", x, y4, kw, kh)); x += kw
    space_w = width - 2 * sym_w - 2 * kw
    keys.append(Key(CODE_SPACE, "space", x, y4, space_w, kh)); x += space_w
    keys.append(Key(CODE_PERIOD, ".", x, y4, kw, kh)); x += kw
    keys.append(Key(CODE_ENTER, "enter", x, y4, sym_w, kh))
    return Geometry("pixel10pro_qwerty_synth", width, height, keys)


def load_or_default(path: str | Path | None = None) -> Geometry:
    """Load `path` (default: eval/data/geometry/pixel10pro_qwerty.json); if it does not
    exist, generate the default layout and write it there."""
    p = Path(path) if path else DEFAULT_GEOMETRY_PATH
    if p.exists():
        return Geometry.load(p)
    g = default_pixel10pro_qwerty()
    if p == DEFAULT_GEOMETRY_PATH:
        g.save(p)
    return g


def ascii_dump(g: Geometry) -> str:
    rows: dict[float, list[Key]] = {}
    for k in g.keys:
        rows.setdefault(round(k.y), []).append(k)
    lines = [f"{g.id}: {g.width:.0f}x{g.height:.0f} px, {len(g.keys)} keys"]
    for y in sorted(rows):
        ks = sorted(rows[y], key=lambda k: k.x)
        lines.append(f"y={y:>6}: " + " ".join(f"[{k.label}@{k.x:.0f}+{k.w:.0f}]" for k in ks))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate", help="write the default Pixel 10 Pro QWERTY geometry")
    g.add_argument("--out", default=str(DEFAULT_GEOMETRY_PATH))
    g.add_argument("--force", action="store_true", help="overwrite if it exists")
    s = sub.add_parser("show", help="ASCII dump of a geometry file")
    s.add_argument("path")
    r = sub.add_parser("resolve", help="resolve a tap to a key")
    r.add_argument("path")
    r.add_argument("x", type=float)
    r.add_argument("y", type=float)
    a = ap.parse_args(argv)

    if a.cmd == "generate":
        out = Path(a.out)
        if out.exists() and not a.force:
            print(f"{out} exists (use --force to overwrite)")
            return 0
        geo = default_pixel10pro_qwerty()
        geo.save(out)
        print(f"wrote {out}")
        print(ascii_dump(geo))
    elif a.cmd == "show":
        print(ascii_dump(Geometry.load(a.path)))
    elif a.cmd == "resolve":
        k = Geometry.load(a.path).resolve(a.x, a.y)
        print(json.dumps(asdict(k)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
