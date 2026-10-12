#!/usr/bin/env python3
"""Typo simulator: sentences -> log-schema JSONL touch traces.

For every character of every word the simulator emits a `touch` down, a `touch` up and a
`key` event positioned on the keyboard geometry, with a noise model that mimics real
thumb typing on a phone:

* per-tap Gaussian noise (sigma as a fraction of key width / height),
* a slowly drifting posture offset (random walk) plus a per-session trapezoid skew
  (offset grows linearly with x) and a small rotation,
* fat-finger taps that land on a random point of a neighbouring key,
* character-level slips: transposition, omission, double-tap, insertion,
* `--novel-rate`: some words are replaced by plausible made-up words (or names) that are
  typed precisely (sigma x 0.4, no slips). They are recorded as-is in `truth` so the
  eval can measure novel-word preservation.

The `code` of each tap is the key whose rect contains the noisy point (nearest key if the
point falls in a gap) - exactly what the keyboard would resolve. The simulator never emits
`backspace`, and a tap that would resolve to a non-letter key is snapped to the nearest
letter key so that words are never split or deleted by accident.

Every sentence ends with its punctuation key and a space. After each sentence a `truth`
event carries the last <= 240 chars of the session text (as the app does) plus a `novel`
list of the novel words in that sentence (an extra field; unknown fields are ignored).

Example:
    synth.py --profile sloppy --seed 1 --out eval/out/sloppy.jsonl
    synth.py --profile novel-heavy --n 200 --out eval/out/novel.jsonl
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
from dataclasses import dataclass, replace, asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import DATA_DIR, strip_punct, write_jsonl, eprint  # noqa: E402
from geometry import (CODE_APOSTROPHE, CODE_PERIOD, CODE_SHIFT, CODE_SPACE, Geometry, Key,  # noqa: E402
                      load_or_default)

SENTENCES_PATH = DATA_DIR / "sentences.txt"
TRUTH_WINDOW = 240
BASE_T = 1_760_000_000_000  # fixed epoch for reproducible traces


@dataclass(frozen=True)
class NoiseParams:
    sigma_x: float = 0.25          # per-tap Gaussian sigma, fraction of key width
    sigma_y: float = 0.30          # per-tap Gaussian sigma, fraction of key height
    drift_step: float = 0.02       # posture random-walk step per tap (key units)
    drift_max: float = 0.12        # posture offset clamp (key units)
    skew: float = 0.08             # trapezoid skew: |offset| at keyboard edges (key units)
    rotation: float = 0.01         # per-session rotation sigma (radians)
    p_neighbor: float = 0.015      # fat finger: tap lands on a random point of a neighbour
    p_transpose: float = 0.006     # swap with the next character
    p_omit: float = 0.008          # skip a character
    p_double: float = 0.005        # tap a character twice
    p_insert: float = 0.004        # insert a neighbouring key
    novel_rate: float = 0.02       # fraction of eligible words replaced by novel words
    novel_sigma_scale: float = 0.4 # novel words are typed precisely
    key_ms: float = 180.0          # median inter-key interval (lognormal)
    key_ms_sigma: float = 0.35     # lognormal sigma of the inter-key interval


PROFILES: dict[str, NoiseParams] = {
    "clean": NoiseParams(sigma_x=0.12, sigma_y=0.15, drift_step=0.01, drift_max=0.10, skew=0.05,
                         rotation=0.005, p_neighbor=0.004, p_transpose=0.002, p_omit=0.002,
                         p_double=0.002, p_insert=0.001, novel_rate=0.01),
    "sloppy": NoiseParams(),
    "very-sloppy": NoiseParams(sigma_x=0.33, sigma_y=0.38, drift_step=0.04, drift_max=0.22, skew=0.15,
                               rotation=0.02, p_neighbor=0.04, p_transpose=0.015, p_omit=0.02,
                               p_double=0.012, p_insert=0.01, novel_rate=0.02),
    "novel-heavy": NoiseParams(novel_rate=0.15),
}

# ---------------------------------------------------------------------------- novel words
NOVEL_SEEDS = [
    "omarchy", "flexnet", "kubeflowy", "zxlib", "sloptype", "heliboard", "kubectl", "hblake",
    "xcantech", "vexlar", "nimbusly", "quorvo", "tralix", "obrenko", "zedbox", "pytorchy",
    "nginxctl", "gravlox", "mistral", "fennwick", "brontide", "kelvish", "sorbital", "dunmore",
    "Tamsin", "Okonkwo", "Brightwater", "Zelmira", "Kovalenko", "Ravindra", "Thorsby", "Ilyana",
    "Castellanos", "Nyberg", "Oyelaran", "Jurgenson", "Mbeki", "Szymanski", "Delphine", "Anouk",
]
_ONSETS = ["b", "br", "ch", "d", "dr", "f", "fl", "g", "gr", "k", "kl", "kr", "m", "n", "p", "pr", "r",
           "s", "sk", "sl", "st", "t", "tr", "v", "z", "th", "sh", "w", "y", "qu", "x", "j", "h", "l"]
_NUCLEI = ["a", "e", "i", "o", "u", "ai", "ou", "ee", "oo", "ia", "ei", "au"]
_CODAS = ["", "", "n", "r", "l", "t", "k", "s", "x", "sh", "ch", "rk", "nt", "st", "m", "ng", "ld", "rn"]


def make_novel_word(rng: random.Random, capitalized: bool, avoid: set[str]) -> str:
    """A pronounceable made-up word (or name when `capitalized`), not in `avoid`."""
    for _ in range(100):
        if rng.random() < 0.3:
            w = rng.choice(NOVEL_SEEDS)
            if w[0].isupper() != capitalized:
                continue
        else:
            n = rng.choice([2, 2, 3, 3, 4])
            w = "".join(rng.choice(_ONSETS) + rng.choice(_NUCLEI) + rng.choice(_CODAS) for _ in range(n))
            if rng.random() < 0.3:
                w += rng.choice(["y", "ly", "ix", "er", "a", "o"])
            if capitalized:
                w = w[:1].upper() + w[1:]
        if 4 <= len(w) <= 12 and w.lower() not in avoid:
            return w
    return "zorvak" if not capitalized else "Zorvak"


# --------------------------------------------------------------------------------- posture
@dataclass
class Posture:
    """Slowly varying thumb geometry for one session."""
    dx: float = 0.0      # random-walk offset (key widths)
    dy: float = 0.0      # random-walk offset (key heights)
    skew_x: float = 0.0  # offset at the right edge (key widths), linear in x
    skew_y: float = 0.0  # offset at the right edge (key heights), linear in x
    rot: float = 0.0     # radians about the keyboard centre

    def step(self, rng: random.Random, p: NoiseParams) -> None:
        self.dx = max(-p.drift_max, min(p.drift_max, self.dx + rng.gauss(0, p.drift_step)))
        self.dy = max(-p.drift_max, min(p.drift_max, self.dy + rng.gauss(0, p.drift_step)))


class Synth:
    def __init__(self, geo: Geometry, params: NoiseParams, rng: random.Random, lexicon: set[str]):
        self.geo = geo
        self.p = params
        self.rng = rng
        self.lexicon = lexicon
        self.kw, self.kh = geo.mean_letter_size()
        self.letters = geo.letter_keys()
        self.period = geo.key_for_code(CODE_PERIOD)
        self.space = geo.key_for_code(CODE_SPACE)
        self.shift = geo.key_for_code(CODE_SHIFT)
        self._neigh: dict[int, list[Key]] = {k.code: geo.neighbours(k) for k in self.letters}
        # stats
        self.n_words = 0
        self.n_novel = 0
        self.n_taps = 0
        self.n_typed_wrong = 0

    # ---- keys for characters --------------------------------------------------------
    def key_for(self, ch: str) -> Key:
        if ch == "'":
            return Key(CODE_APOSTROPHE, "'", self.period.x, self.period.y, self.period.w, self.period.h)
        k = self.geo.key_for_char(ch)
        if k is None:
            # Unknown symbol: lives on the symbols layer; approximate with the period key.
            return Key(ord(ch), ch, self.period.x, self.period.y, self.period.w, self.period.h)
        return k

    # ---- noise ----------------------------------------------------------------------
    def noisy_point(self, k: Key, post: Posture, sigma_scale: float, fat_finger: bool) -> tuple[float, float]:
        rng, g = self.rng, self.geo
        if fat_finger:
            nb = self._neigh.get(k.code) or []
            if nb:
                t = rng.choice(nb)
                return (t.x + rng.random() * t.w, t.y + rng.random() * t.h)
        sx = self.p.sigma_x * sigma_scale * k.w
        sy = self.p.sigma_y * sigma_scale * k.h
        u = (k.cx - g.width / 2) / (g.width / 2)  # -1 .. 1 across the keyboard
        x = k.cx + rng.gauss(0, sx) + post.dx * self.kw + post.skew_x * u * self.kw - post.rot * (k.cy - g.height / 2)
        y = k.cy + rng.gauss(0, sy) + post.dy * self.kh + post.skew_y * u * self.kh + post.rot * (k.cx - g.width / 2)
        x = min(max(x, 0.0), g.width - 1e-3)
        y = min(max(y, 0.0), g.height - 1e-3)
        return x, y

    def resolve_letter(self, x: float, y: float) -> Key:
        k = self.geo.resolve(x, y)
        if k.is_letter():
            return k
        return min(self.letters, key=lambda kk: (kk.cx - x) ** 2 + (kk.cy - y) ** 2)

    def plan_chars(self, word: str) -> list[str]:
        """Apply character-level slips to the intended character sequence."""
        rng, p = self.rng, self.p
        chars = list(word)
        out: list[str] = []
        i = 0
        while i < len(chars):
            c = chars[i]
            if i + 1 < len(chars) and c.isalpha() and chars[i + 1].isalpha() and rng.random() < p.p_transpose:
                out.extend([chars[i + 1], c])
                i += 2
                continue
            if len(chars) > 2 and c.isalpha() and rng.random() < p.p_omit:
                i += 1
                continue
            out.append(c)
            if c.isalpha() and rng.random() < p.p_double:
                out.append(c)
            if c.isalpha() and rng.random() < p.p_insert:
                nb = self._neigh.get(ord(c.lower())) or []
                if nb:
                    out.append(rng.choice(nb).label)
            i += 1
        return out if out else chars

    # ---- time -------------------------------------------------------------------------
    def dt(self, scale: float = 1.0) -> int:
        return int(round(self.rng.lognormvariate(math.log(self.p.key_ms * scale), self.p.key_ms_sigma)))

    def hold(self) -> int:
        return int(round(self.rng.lognormvariate(math.log(70.0), 0.3)))

    # ---- event emission ----------------------------------------------------------------
    def tap(self, ev: list[dict], sid: str, t: int, k: Key, x: float, y: float, code: int,
            emit_key: bool = True) -> int:
        rng = self.rng
        pr = round(min(1.0, max(0.05, rng.gauss(0.45, 0.08))), 3)
        sz = round(min(1.0, max(0.02, rng.gauss(0.14, 0.03))), 3)
        up = t + self.hold()
        ev.append({"ev": "touch", "t": t, "sid": sid, "x": round(x, 1), "y": round(y, 1), "p": pr, "s": sz, "a": "d", "code": code})
        ev.append({"ev": "touch", "t": up, "sid": sid, "x": round(x + rng.gauss(0, 1.5), 1), "y": round(y + rng.gauss(0, 1.5), 1),
                   "p": round(pr * 0.6, 3), "s": sz, "a": "u", "code": code})
        if emit_key:
            ev.append({"ev": "key", "t": up, "sid": sid, "code": code, "x": round(x, 1), "y": round(y, 1), "repeat": False})
        self.n_taps += 1
        return up

    def type_word(self, ev: list[dict], sid: str, t: int, word: str, post: Posture, novel: bool,
                  sentence_start: bool) -> tuple[int, str]:
        """Type `word` (no trailing punctuation). Returns (t, literal as the keyboard resolved)."""
        rng, p = self.rng, self.p
        chars = list(word) if novel else self.plan_chars(word)
        scale = p.novel_sigma_scale if novel else 1.0
        literal: list[str] = []
        for idx, ch in enumerate(chars):
            t += self.dt()
            upper = ch.isupper()
            if upper and not (sentence_start and idx == 0) and self.shift is not None:
                sx, sy = self.noisy_point(self.shift, post, scale, False)
                t = self.tap(ev, sid, t, self.shift, sx, sy, CODE_SHIFT, emit_key=False)
                t += self.dt(0.8)
            k = self.key_for(ch)
            fat = (not novel) and k.is_letter() and rng.random() < p.p_neighbor
            x, y = self.noisy_point(k, post, scale, fat)
            post.step(rng, p)
            if k.is_letter():
                rk = self.resolve_letter(x, y)
                out_ch = rk.label.upper() if upper else rk.label
                code = ord(out_ch)
            else:
                rk, out_ch, code = k, ch, k.code
            t = self.tap(ev, sid, t, rk, x, y, code)
            literal.append(out_ch)
        return t, "".join(literal)

    def type_separator(self, ev: list[dict], sid: str, t: int, ch: str, post: Posture) -> int:
        k = self.space if ch == " " else self.key_for(ch)
        x, y = self.noisy_point(k, post, 1.0, False)
        return self.tap(ev, sid, t, k, x, y, k.code if ch != " " else CODE_SPACE)

    def type_sentence(self, ev: list[dict], sid: str, t: int, sentence: str, post: Posture) -> tuple[int, str, list[str]]:
        """Returns (t, truth sentence text (with novel substitutions), novel words)."""
        rng, p = self.rng, self.p
        tokens = sentence.split()
        truth_tokens: list[str] = []
        novel_words: list[str] = []
        for wi, tok in enumerate(tokens):
            core = strip_punct(tok)
            lead = tok[: len(tok) - len(tok.lstrip("\"'([{"))]
            trail = tok[len(lead) + len(core):]
            is_novel = False
            if (p.novel_rate > 0 and core.isalpha() and len(core) >= 4 and wi > 0
                    and rng.random() < p.novel_rate):
                core = make_novel_word(rng, core[0].isupper(), self.lexicon)
                is_novel = True
                novel_words.append(core)
            t += self.dt(1.4 if wi else 1.0)
            t, literal = self.type_word(ev, sid, t, core, post, is_novel, sentence_start=(wi == 0))
            self.n_words += 1
            self.n_novel += int(is_novel)
            if literal.lower() != core.lower():
                self.n_typed_wrong += 1
            for pc in trail:
                t += self.dt(1.1)
                t = self.type_separator(ev, sid, t, pc, post)
            truth_tokens.append(lead + core + trail)
            t += self.dt(1.3)
            t = self.type_separator(ev, sid, t, " ", post)
        return t, " ".join(truth_tokens), novel_words

    def session(self, sid: str, t: int, sentences: list[str]) -> tuple[list[dict], int]:
        rng, p = self.rng, self.p
        post = Posture(dx=rng.gauss(0, p.drift_max / 3), dy=rng.gauss(0, p.drift_max / 3),
                       skew_x=rng.uniform(-p.skew, p.skew), skew_y=rng.uniform(-p.skew, p.skew),
                       rot=rng.gauss(0, p.rotation))
        ev: list[dict] = [{"ev": "session_start", "t": t, "sid": sid, "pkg": "dev.sloptype.synth", "inputType": 1,
                           "imeOptions": 0, "geom": self.geo.to_dict(), "device": "synth", "app_version": "synth-0.1",
                           "engine_config_hash": ""}]
        buffer = ""
        for si, s in enumerate(sentences):
            if si:
                t += int(rng.lognormvariate(math.log(1200.0), 0.5))
            t, truth_text, novel = self.type_sentence(ev, sid, t, s, post)
            buffer = (buffer + " " + truth_text).strip() if buffer else truth_text
            tr = {"ev": "truth", "t": t, "sid": sid, "text": buffer[-TRUTH_WINDOW:]}
            if novel:
                tr["novel"] = novel
            ev.append(tr)
        t += 500
        ev.append({"ev": "session_end", "t": t, "sid": sid})
        return ev, t


def load_sentences(path: str | Path = SENTENCES_PATH) -> list[str]:
    with open(path, encoding="utf-8") as f:
        return [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]


def corpus_words(sentences: list[str]) -> set[str]:
    return {strip_punct(w).lower() for s in sentences for w in s.split() if strip_punct(w)}


def add_noise_args(ap: argparse.ArgumentParser) -> None:
    ap.add_argument("--profile", choices=sorted(PROFILES), default="sloppy")
    for f, v in asdict(NoiseParams()).items():
        ap.add_argument("--" + f.replace("_", "-"), type=float, default=None, help=f"override profile value (default {v})")


def params_from_args(a: argparse.Namespace) -> NoiseParams:
    p = PROFILES[a.profile]
    overrides = {f: getattr(a, f) for f in asdict(NoiseParams()) if getattr(a, f, None) is not None}
    return replace(p, **overrides)


def generate(sentences: list[str], geo: Geometry, params: NoiseParams, seed: int,
             session_min: int = 2, session_max: int = 5, sid_prefix: str = "synth") -> tuple[list[dict], dict]:
    """Generate a full trace (list of events) and a stats dict."""
    rng = random.Random(seed)
    syn = Synth(geo, params, rng, corpus_words(sentences))
    events: list[dict] = []
    t = BASE_T + seed * 86_400_000
    i = 0
    n_sess = 0
    while i < len(sentences):
        n = rng.randint(session_min, session_max)
        chunk = sentences[i:i + n]
        i += n
        n_sess += 1
        ev, t = syn.session(f"{sid_prefix}-{seed}-{n_sess:04d}", t, chunk)
        events.extend(ev)
        t += int(rng.lognormvariate(math.log(60_000.0), 1.0))
    stats = {"sessions": n_sess, "sentences": len(sentences), "words": syn.n_words, "novel_words": syn.n_novel,
             "taps": syn.n_taps, "typed_wrong": syn.n_typed_wrong,
             "typed_wrong_rate": round(syn.n_typed_wrong / max(1, syn.n_words), 4), "params": asdict(params)}
    return events, stats


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sentences", default=str(SENTENCES_PATH), help="one sentence per line")
    ap.add_argument("--geometry", default=None, help="KeyboardGeometry JSON (default: eval/data/geometry/pixel10pro_qwerty.json)")
    ap.add_argument("--out", required=True, help="output JSONL trace")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--n", type=int, default=0, help="use only the first N sentences (after --shuffle)")
    ap.add_argument("--shuffle", action="store_true", help="shuffle sentences (seeded)")
    ap.add_argument("--session-min", type=int, default=2, help="min sentences per session")
    ap.add_argument("--session-max", type=int, default=5, help="max sentences per session")
    ap.add_argument("--stats", default=None, help="write generation stats JSON here")
    add_noise_args(ap)
    a = ap.parse_args(argv)

    sentences = load_sentences(a.sentences)
    if a.shuffle:
        random.Random(a.seed).shuffle(sentences)
    if a.n > 0:
        sentences = sentences[: a.n]
    geo = load_or_default(a.geometry)
    params = params_from_args(a)
    events, stats = generate(sentences, geo, params, a.seed, a.session_min, a.session_max)
    n = write_jsonl(a.out, events)
    stats["events"] = n
    stats["profile"] = a.profile
    stats["seed"] = a.seed
    if a.stats:
        Path(a.stats).parent.mkdir(parents=True, exist_ok=True)
        with open(a.stats, "w", encoding="utf-8") as f:
            json.dump(stats, f, indent=2)
    eprint(f"synth: profile={a.profile} seed={a.seed} sessions={stats['sessions']} sentences={stats['sentences']} "
           f"words={stats['words']} novel={stats['novel_words']} taps={stats['taps']} "
           f"typed_wrong_rate={stats['typed_wrong_rate']} -> {a.out} ({n} events)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
