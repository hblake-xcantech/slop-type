#!/usr/bin/env python3
"""Build the on-device English lexicon + n-gram tables shipped in the engine.

Input: Peter Norvig's count_1w.txt / count_2w.txt (Google Web Trillion Word Corpus
counts, https://norvig.com/ngrams/). Output: gzipped TSV files in the engine resources.

Usage: build_lexicon.py <count_1w.txt> <count_2w.txt> <out_dir> [--unigrams N] [--bigrams N]
"""
import argparse
import gzip
import math
import os
import re
import sys

WORD_RE = re.compile(r"^[a-z][a-z']{0,23}$")

CONTRACTIONS = {
    "i'm": "im", "i've": "ive", "i'll": "ill", "i'd": "id", "you're": "youre", "you've": "youve", "you'll": "youll",
    "you'd": "youd", "he's": "hes", "he'll": "hell", "he'd": "hed", "she's": "shes", "she'll": "shell", "she'd": "shed",
    "it's": "its", "it'll": "itll", "it'd": "itd", "we're": "were", "we've": "weve", "we'll": "well", "we'd": "wed",
    "they're": "theyre", "they've": "theyve", "they'll": "theyll", "they'd": "theyd", "that's": "thats", "that'll": "thatll",
    "there's": "theres", "there'll": "therell", "here's": "heres", "what's": "whats", "what'll": "whatll", "who's": "whos",
    "who'll": "wholl", "where's": "wheres", "when's": "whens", "how's": "hows", "why's": "whys", "let's": "lets",
    "don't": "dont", "doesn't": "doesnt", "didn't": "didnt", "can't": "cant", "couldn't": "couldnt", "won't": "wont",
    "wouldn't": "wouldnt", "shouldn't": "shouldnt", "isn't": "isnt", "aren't": "arent", "wasn't": "wasnt", "weren't": "werent",
    "haven't": "havent", "hasn't": "hasnt", "hadn't": "hadnt", "mustn't": "mustnt", "needn't": "neednt", "ain't": "aint",
    "would've": "wouldve", "could've": "couldve", "should've": "shouldve", "might've": "mightve", "must've": "mustve",
    "y'all": "yall", "ma'am": "maam", "o'clock": "oclock", "rock'n'roll": "rocknroll",
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("count_1w")
    ap.add_argument("count_2w")
    ap.add_argument("out_dir")
    ap.add_argument("--unigrams", type=int, default=120_000)
    ap.add_argument("--bigrams", type=int, default=220_000)
    args = ap.parse_args()

    uni = []
    with open(args.count_1w, encoding="utf-8", errors="replace") as f:
        for line in f:
            w, c = line.rstrip("\n").split("\t")
            if not WORD_RE.match(w):
                continue
            uni.append((w, int(c)))
    uni.sort(key=lambda t: -t[1])
    uni = uni[: args.unigrams]
    total = sum(c for _, c in uni)
    counts = dict(uni)
    # Norvig's corpus has no apostrophes: synthesize contractions from their bare forms.
    for contraction, bare in CONTRACTIONS.items():
        base = counts.get(bare)
        if base is None:
            continue
        uni.append((contraction, int(base * 1.5)))
    uni.sort(key=lambda t: -t[1])
    vocab = {w for w, _ in uni}

    bi = []
    with open(args.count_2w, encoding="utf-8", errors="replace") as f:
        for line in f:
            pair, c = line.rstrip("\n").split("\t")
            parts = pair.split(" ")
            if len(parts) != 2:
                continue
            a, b = parts
            if a not in vocab or b not in vocab:
                continue
            bi.append((a, b, int(c)))
    bi.sort(key=lambda t: -t[2])
    bi = bi[: args.bigrams]

    os.makedirs(args.out_dir, exist_ok=True)
    with gzip.open(os.path.join(args.out_dir, "en_unigrams.tsv.gz"), "wt", encoding="utf-8") as f:
        f.write(f"#total\t{total}\n")
        for w, c in uni:
            # store log10 prob rounded to 3 decimals to keep the file small
            f.write(f"{w}\t{math.log10(c / total):.3f}\n")
    with gzip.open(os.path.join(args.out_dir, "en_bigrams.tsv.gz"), "wt", encoding="utf-8") as f:
        for a, b, c in bi:
            f.write(f"{a}\t{b}\t{c}\n")
    print(f"unigrams={len(uni)} bigrams={len(bi)} total_count={total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
