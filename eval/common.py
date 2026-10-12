#!/usr/bin/env python3
"""Shared helpers for the eval harness: JSONL IO, word normalisation and the
truth/commit matching rule.

Matching rule (DESIGN.md / replay.py): a commit is correct for a truth word if, after
stripping trailing punctuation from both, they are equal case-insensitively, unless the
truth word is capitalised, in which case the comparison is exact.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Iterable, Iterator

EVAL_DIR = Path(__file__).resolve().parent
DATA_DIR = EVAL_DIR / "data"
LEXICON_PATH = DATA_DIR / "lexicon.txt"

# Word-internal characters: letters plus apostrophe. Everything else is a separator.
TRAILING_PUNCT = ".,!?;:\"')]}"
LEADING_PUNCT = "\"'([{"

# Key codes treated as separators by the engine contract (space, punctuation, enter).
SEPARATOR_CODES = {10, 32, 33, 44, 46, 58, 59, 63}


def read_jsonl(path: str | Path) -> list[dict]:
    out: list[dict] = []
    with open(path, encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError as e:
                raise ValueError(f"{path}:{ln}: bad JSON: {e}") from e
    return out


def iter_jsonl(path: str | Path) -> Iterator[dict]:
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield json.loads(line)


def write_jsonl(path: str | Path, rows: Iterable[dict]) -> int:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")))
            f.write("\n")
            n += 1
    return n


def strip_punct(word: str) -> str:
    """Strip leading/trailing punctuation but keep internal apostrophes (don't)."""
    return word.strip(LEADING_PUNCT + TRAILING_PUNCT)


def tokenize(text: str) -> list[str]:
    """Whitespace tokenisation; tokens that are pure punctuation are dropped."""
    return [t for t in text.split() if strip_punct(t)]


def word_matches(commit: str, truth: str) -> bool:
    c, t = strip_punct(commit), strip_punct(truth)
    if not t:
        return not c
    if t[0].isupper():
        return c == t
    return c.lower() == t.lower()


def same_word(a: str, b: str) -> bool:
    """Case-insensitive, punctuation-stripped equality (used for typed == truth)."""
    return strip_punct(a).lower() == strip_punct(b).lower()


def load_lexicon(path: str | Path = LEXICON_PATH) -> dict[str, int]:
    """`word<TAB>count` per line (count optional). Returns {word: count}."""
    lex: dict[str, int] = {}
    p = Path(path)
    if not p.exists():
        return lex
    with open(p, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            w = parts[0].strip().lower()
            c = int(parts[1]) if len(parts) > 1 and parts[1].strip().isdigit() else 1
            if w:
                lex[w] = max(lex.get(w, 0), c)
    return lex


def percentile(values: list[float], pct: float) -> float:
    """Nearest-rank percentile (pct in [0, 100]); 0.0 for an empty list."""
    if not values:
        return 0.0
    vs = sorted(values)
    k = max(0, min(len(vs) - 1, int(round(pct / 100.0 * (len(vs) - 1)))))
    return float(vs[k])


def find_trace_files(spec: str | Path) -> list[Path]:
    """A file, or a directory searched recursively for *.jsonl (sorted)."""
    p = Path(spec)
    if p.is_dir():
        return sorted(q for q in p.rglob("*.jsonl") if q.is_file())
    if p.exists():
        return [p]
    raise FileNotFoundError(str(p))


def eprint(*a: object) -> None:
    print(*a, file=sys.stderr)


def ensure_dir(p: str | Path) -> Path:
    q = Path(p)
    os.makedirs(q, exist_ok=True)
    return q
