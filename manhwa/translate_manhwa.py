#!/usr/bin/env python3
"""
Manhwa / manga -> Kurdish Sorani (ckb) translator.  100% free, NO paid API key.

Pipeline:  long strip --overlapping tiles--> Tesseract OCR per tile
           --spatial line/bubble grouping--> translate --> Sorani image

The old whole-image OCR pass collapsed tall webtoons into one bad segment.
Image mode therefore NEVER OCRs a tall page in a single pass: it cuts the
page into overlapping horizontal tiles, OCRs each tile, maps every word back
to full-page coordinates, removes seam duplicates, and refuses obviously
implausible results through the caller's quality gate.

Free-forever strategy (so a free API never "burns"):
  1. google-free : Google Translate web endpoint, NO key (tl=ckb = Sorani)
  2. cache       : SQLite cache - every sentence is translated ONCE, ever.
                   Manhwa repeat lines constantly ("What?!", names...).
  3. nllb        : fully offline Meta NLLB-200 model (eng_Latn -> ckb_Arab),
                   optional/heavy, never expires.  (--provider nllb)
  glossary.json  : your own exact phrases/names always win over any provider.

Examples:
  python translate_manhwa.py --text "I will protect you!"
  python translate_manhwa.py --file chapter1_en.txt
  python translate_manhwa.py --image page1.jpg --render
  python translate_manhwa.py --image page1.png --src ko   # Korean raw (needs kor OCR data)

OCR needs the free Tesseract binary:
  Ubuntu/Debian: sudo apt install tesseract-ocr tesseract-ocr-kor fonts-noto-core
  Windows:       https://github.com/UB-Mannheim/tesseract/wiki
Only translate material you have the rights to / for personal use.
"""
from __future__ import annotations

import argparse, csv, io, json, math, os, re, sqlite3, subprocess, sys, tempfile, time, urllib.parse, urllib.request
from dataclasses import dataclass, field, replace
from pathlib import Path

# ---------------------------------------------------------------- basics
def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "")).strip()

def split_long_text(text: str, limit: int = 1600) -> list[str]:
    """Split on sentence boundaries so one request never gets huge."""
    text = normalize(text)
    if len(text) <= limit:
        return [text] if text else []
    out, buf = [], ""
    for part in re.split(r"(?<=[.!?؟…])\s+", text):
        if len(buf) + len(part) + 1 > limit and buf:
            out.append(buf); buf = part
        else:
            buf = f"{buf} {part}".strip()
    if buf: out.append(buf)
    return out

# ---------------------------------------------------------------- cache
class TranslationCache:
    """SQLite cache: translate once, reuse free forever."""
    def __init__(self, path: str | Path):
        self.db = sqlite3.connect(str(path))
        self.db.execute("""CREATE TABLE IF NOT EXISTS t(
            src TEXT, tgt TEXT, provider TEXT, source TEXT, result TEXT,
            hits INTEGER DEFAULT 0, PRIMARY KEY(src,tgt,provider,source))""")
        self.db.commit()

    def get(self, src, tgt, provider, text):
        row = self.db.execute("SELECT result FROM t WHERE src=? AND tgt=? AND provider=? AND source=?",
                              (src, tgt, provider, text)).fetchone()
        if row:
            self.db.execute("UPDATE t SET hits=hits+1 WHERE src=? AND tgt=? AND provider=? AND source=?",
                            (src, tgt, provider, text)); self.db.commit()
            return row[0]
        return None

    def set(self, src, tgt, provider, text, result):
        self.db.execute("INSERT OR REPLACE INTO t(src,tgt,provider,source,result) VALUES(?,?,?,?,?)",
                        (src, tgt, provider, text, result)); self.db.commit()

    def stats(self):
        n, h = self.db.execute("SELECT COUNT(*),COALESCE(SUM(hits),0) FROM t").fetchone()
        return {"entries": n, "reused_times": h}

# ---------------------------------------------------------------- providers
def parse_google_payload(payload) -> str:
    """gtx response: [[['translated','source',...],...], ...]"""
    try:
        return "".join(seg[0] for seg in payload[0] if seg and seg[0]).strip()
    except Exception as e:
        raise ValueError(f"unexpected Google response: {str(payload)[:120]}") from e

def translate_google_free(text, src="en", tgt="ckb", timeout=20) -> str:
    q = urllib.parse.urlencode({"client": "gtx", "sl": src, "tl": tgt, "dt": "t", "q": text})
    req = urllib.request.Request(f"https://translate.googleapis.com/translate_a/single?{q}",
                                 headers={"User-Agent": "Mozilla/5.0 manhwa-ckb/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return parse_google_payload(json.loads(r.read().decode("utf-8")))

def translate_mymemory_free(text, src="en", tgt="ckb", timeout=20) -> str:
    q = urllib.parse.urlencode({"q": text, "langpair": f"{src}|{tgt}"})
    req = urllib.request.Request(f"https://api.mymemory.translated.net/get?{q}",
                                 headers={"User-Agent": "manhwa-ckb/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.loads(r.read().decode("utf-8"))
    out = (data.get("responseData") or {}).get("translatedText", "").strip()
    if not out or "INVALID" in out.upper() or "NO QUERY" in out.upper():
        raise ValueError(f"MyMemory: {out or data.get('responseDetails')}")
    return out

class NLLBProvider:
    """Optional fully-offline provider (heavy one-time ~2.5GB download)."""
    LANG = {"en": "eng_Latn", "ko": "kor_Hang", "ckb": "ckb_Arab", "ar": "arb_Arab", "tr": "tur_Latn"}
    def __init__(self, model="facebook/nllb-200-distilled-600M"):
        try:
            from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
        except ImportError as e:
            raise RuntimeError("NLLB نیە: pip install -r requirements-nllb.txt") from e
        self.tok = AutoTokenizer.from_pretrained(model)
        self.model = AutoModelForSeq2SeqLM.from_pretrained(model)

    def __call__(self, text, src="en", tgt="ckb", timeout=None):
        self.tok.src_lang = self.LANG.get(src, src)
        enc = self.tok(text, return_tensors="pt")
        bos = self.tok.convert_tokens_to_ids(self.LANG.get(tgt, tgt))
        out = self.model.generate(**enc, forced_bos_token_id=bos, max_length=256)
        return self.tok.batch_decode(out, skip_special_tokens=True)[0].strip()

PROVIDER_FUNCS = {"google-free": translate_google_free, "mymemory": translate_mymemory_free}

# ---------------------------------------------------------------- translator
@dataclass
class Translator:
    providers: list[str] = field(default_factory=lambda: ["google-free"])
    src: str = "en"; tgt: str = "ckb"
    cache: TranslationCache | None = None
    glossary: dict = field(default_factory=dict)
    delay: float = 0.4            # be polite -> free endpoints last much longer
    _funcs: dict = field(default_factory=dict)

    def _provider(self, name):
        if name not in self._funcs:
            if name == "mock":   # offline tests/demo: glossary hit or marked echo
                self._funcs[name] = lambda t, src="en", tgt="ckb", timeout=None: \
                    self.glossary.get(normalize(t).lower(), f"[DEMO-CKB] {t}")
            elif name == "nllb":
                self._funcs[name] = NLLBProvider()
            else:
                self._funcs[name] = PROVIDER_FUNCS[name]
        return self._funcs[name]

    def translate_one(self, text: str) -> str:
        text = normalize(text)
        if not text: return ""
        if text.lower() in self.glossary: return self.glossary[text.lower()]
        joined = []
        for chunk in split_long_text(text):
            hit = self.cache.get(self.src, self.tgt, "+".join(self.providers), chunk) if self.cache else None
            if hit is not None:
                joined.append(hit); continue
            err = None
            for name in self.providers:            # chain: one burns -> next one
                try:
                    res = normalize(self._provider(name)(chunk, self.src, self.tgt))
                    if not res: raise ValueError("empty result")
                    if self.cache: self.cache.set(self.src, self.tgt, "+".join(self.providers), chunk, res)
                    joined.append(res); err = None
                    if self.delay: time.sleep(self.delay)
                    break
                except Exception as e:               # keep trying the next provider
                    err = f"{name}: {e}"; time.sleep(min(2.0, self.delay * 3))
            if err: raise RuntimeError(f"هەموو وەرگێڕەکان شکستیان هێنا — {err}")
        return " ".join(joined)

    def translate_many(self, texts: list[str]) -> list[str]:
        done, out = {}, []
        for t in texts:                              # dedupe repeated bubbles in a page
            key = normalize(t)
            if key not in done: done[key] = self.translate_one(key)
            out.append(done[key])
        return out

def load_glossary(path: str | Path | None) -> dict:
    if not path or not Path(path).exists(): return {}
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    phrases = raw.get("phrases", raw) if isinstance(raw, dict) else {}
    return {normalize(k).lower(): v for k, v in phrases.items()}

# ---------------------------------------------------------------- OCR
TESS_LANG = {"en": "eng", "ko": "kor", "ar": "ara", "tr": "tur", "ja": "jpn", "zh": "chi_sim"}

class OCRError(RuntimeError):
    pass

@dataclass
class Segment:
    text: str
    box: tuple[int, int, int, int]  # tight OCR text box: x, y, w, h
    ckb: str = ""
    confidence: float | None = None
    render_box: tuple[int, int, int, int] | None = None  # bubble/interior box for typesetting
    bubble_detected: bool = False
    bubble_id: int | None = None  # one physical bubble -> one typeset segment
    placement_confirmed: bool = False  # flat narration area even without a bubble outline

@dataclass(frozen=True)
class Tile:
    y0: int; y1: int; core_y0: int; core_y1: int

# --- small box helpers -----------------------------------------------------
def _xyxy(box) -> tuple[int, int, int, int]:
    x, y, w, h = box
    return int(x), int(y), int(x + w), int(y + h)

def _box_from_xyxy(x0, y0, x1, y1) -> tuple[int, int, int, int]:
    return int(x0), int(y0), max(1, int(x1 - x0)), max(1, int(y1 - y0))

def _overlap(a0, a1, b0, b1) -> int | float:
    return max(0, min(a1, b1) - max(a0, b0))

def _center(box) -> tuple[float, float]:
    x, y, w, h = box
    return x + w / 2, y + h / 2

def box_iou(a, b) -> float:
    ax0, ay0, ax1, ay1 = _xyxy(a); bx0, by0, bx1, by1 = _xyxy(b)
    inter = _overlap(ax0, ax1, bx0, bx1) * _overlap(ay0, ay1, by0, by1)
    if inter <= 0:
        return 0.0
    union = max(1, (ax1 - ax0) * (ay1 - ay0) + (bx1 - bx0) * (by1 - by0) - inter)
    return inter / union

def expand_box(box, pad_x: int, pad_y: int, image_size: tuple[int, int] | None = None):
    x0, y0, x1, y1 = _xyxy(box)
    x0 -= pad_x; y0 -= pad_y; x1 += pad_x; y1 += pad_y
    if image_size:
        width, height = image_size
        x0 = max(0, x0); y0 = max(0, y0); x1 = min(width, x1); y1 = min(height, y1)
    return _box_from_xyxy(x0, y0, x1, y1)

def _union_box(boxes):
    x0 = min(_xyxy(b)[0] for b in boxes); y0 = min(_xyxy(b)[1] for b in boxes)
    x1 = max(_xyxy(b)[2] for b in boxes); y1 = max(_xyxy(b)[3] for b in boxes)
    return _box_from_xyxy(x0, y0, x1, y1)

def _int_field(row, name, default=0) -> int:
    try:
        return int(float(row.get(name, default) or default))
    except (TypeError, ValueError):
        return default

def parse_tesseract_words(tsv: str, min_conf: float = 35) -> list[dict]:
    """Return usable OCR words. Punctuation-only specks are usually artwork."""
    words = []
    for row in csv.DictReader(io.StringIO(tsv), delimiter="\t", quoting=csv.QUOTE_NONE):
        word = normalize(row.get("text", ""))
        try:
            conf = float(row.get("conf", -1))
        except (TypeError, ValueError):
            conf = -1
        if not word or conf < min_conf or not any(ch.isalnum() for ch in word):
            continue
        words.append({
            "text": word,
            "x": _int_field(row, "left"), "y": _int_field(row, "top"),
            "w": max(1, _int_field(row, "width", 1)), "h": max(1, _int_field(row, "height", 1)),
            "conf": conf,
        })
    return words

def _median(values, default=16.0) -> float:
    vals = sorted(float(v) for v in values if v)
    if not vals:
        return default
    return vals[len(vals) // 2] if len(vals) % 2 else (vals[len(vals) // 2 - 1] + vals[len(vals) // 2]) / 2

def _word_box(w):
    return (w["x"], w["y"], w["w"], w["h"])

def _line_from_words(words):
    words = sorted(words, key=lambda w: w["x"])
    chars = sum(max(1, len(w["text"])) for w in words)
    conf = sum(w["conf"] * max(1, len(w["text"])) for w in words) / chars
    return {"words": words, "text": " ".join(w["text"] for w in words),
            "box": _union_box([_word_box(w) for w in words]), "conf": conf}

def dedupe_words(words: list[dict]) -> list[dict]:
    """Merge the same word found by two OCR passes, keeping higher confidence."""
    kept: list[dict] = []
    for word in words:
        duplicate_at = None
        wbox = _word_box(word); wx, wy = _center(wbox)
        for idx, old in enumerate(kept):
            obox = _word_box(old)
            if box_iou(wbox, obox) >= 0.45:
                duplicate_at = idx; break
            if normalize(word["text"]).lower() == normalize(old["text"]).lower():
                ox, oy = _center(obox)
                allowed = max(12.0, 0.75 * max(word["h"], old["h"]))
                if math.hypot(wx - ox, wy - oy) <= allowed:
                    duplicate_at = idx; break
        if duplicate_at is None:
            kept.append(word)
        elif float(word.get("conf", 0)) > float(kept[duplicate_at].get("conf", 0)):
            kept[duplicate_at] = word
    return kept

def _split_line_on_large_gaps(words, median_h):
    """Split a row containing two far-apart bubbles/columns at the big x-gap."""
    if len(words) < 2:
        return [words]
    groups, current = [], [words[0]]
    gap_limit = max(48.0, 3.0 * median_h)
    for prev, word in zip(words, words[1:]):
        gap = word["x"] - (prev["x"] + prev["w"])
        if gap > gap_limit:
            groups.append(current); current = [word]
        else:
            current.append(word)
    groups.append(current)
    return groups

def group_words_into_lines(words: list[dict]) -> list[dict]:
    """Spatial line grouping (not Tesseract block numbers, which merge pages)."""
    if not words:
        return []
    median_h = _median([w["h"] for w in words])
    raw_lines: list[list[dict]] = []
    for word in sorted(words, key=lambda w: (w["y"] + w["h"] / 2, w["x"])):
        wy0, wy1 = word["y"], word["y"] + word["h"]
        candidates = []
        for idx, line_words in enumerate(raw_lines):
            box = _union_box([_word_box(w) for w in line_words])
            _x0, ly0, _x1, ly1 = _xyxy(box)
            overlap_ratio = _overlap(wy0, wy1, ly0, ly1) / max(1.0, min(word["h"], box[3]))
            center_gap = abs((wy0 + wy1) / 2 - (ly0 + ly1) / 2)
            x_gap = max(0, max(word["x"], box[0]) - min(word["x"] + word["w"], box[0] + box[2]))
            if (overlap_ratio >= 0.45 or center_gap <= 0.55 * median_h) and x_gap <= max(140.0, 5 * median_h):
                candidates.append((center_gap, x_gap, idx))
        if candidates:
            raw_lines[min(candidates)[2]].append(word)
        else:
            raw_lines.append([word])
    lines = []
    for line_words in raw_lines:
        for group in _split_line_on_large_gaps(sorted(line_words, key=lambda w: w["x"]), median_h):
            lines.append(_line_from_words(group))
    return sorted(lines, key=lambda l: (l["box"][1], l["box"][0]))

def _join_segment_lines(lines) -> str:
    text = ""
    for line in lines:
        part = line["text"].strip()
        if not part:
            continue
        if text.endswith("-") and part[:1].islower():
            text = text[:-1] + part
        else:
            text = f"{text} {part}".strip()
    return normalize(text)

def group_lines_into_segments(lines: list[dict]) -> list[Segment]:
    """Join vertically-stacked lines of one bubble; keep separate bubbles apart."""
    if not lines:
        return []
    median_h = _median([l["box"][3] for l in lines])
    clusters: list[list[dict]] = []
    for line in sorted(lines, key=lambda l: (l["box"][1], l["box"][0])):
        lx0, ly0, lx1, _ly1 = _xyxy(line["box"])
        best = None
        for idx, cluster in enumerate(clusters):
            prev = cluster[-1]
            px0, _py0, px1, py1 = _xyxy(prev["box"])
            gap = ly0 - py1
            if gap < -0.25 * median_h or gap > max(20.0, 1.10 * median_h):
                continue
            x_overlap_ratio = _overlap(lx0, lx1, px0, px1) / max(1.0, min(lx1 - lx0, px1 - px0))
            centers_close = abs(_center(line["box"])[0] - _center(prev["box"])[0]) <= 0.55 * max(lx1 - lx0, px1 - px0)
            if x_overlap_ratio >= 0.22 or centers_close:
                score = (gap, -x_overlap_ratio, idx)
                if best is None or score < best:
                    best = score
        if best is None:
            clusters.append([line])
        else:
            clusters[best[2]].append(line)
    segs = []
    for cluster in clusters:
        cluster.sort(key=lambda l: (l["box"][1], l["box"][0]))
        chars = sum(max(1, len(l["text"])) for l in cluster)
        conf = sum(l["conf"] * max(1, len(l["text"])) for l in cluster) / chars
        segs.append(Segment(_join_segment_lines(cluster), _union_box([l["box"] for l in cluster]),
                            confidence=round(conf, 1)))
    return sort_reading_order(segs)

def parse_tesseract_tsv(tsv: str, min_conf: float = 35) -> list[Segment]:
    """Parse one TSV using geometry. This deliberately ignores block/par IDs."""
    return group_lines_into_segments(group_words_into_lines(parse_tesseract_words(tsv, min_conf)))

def sort_reading_order(segs: list[Segment]) -> list[Segment]:
    return sorted(segs, key=lambda s: (s.box[1], s.box[0]))

def segment_quality_score(seg: Segment) -> float:
    conf_factor = 0.55 + ((seg.confidence or 55) / 100.0)
    score = min(len(seg.text), 160) * conf_factor + 8
    # A bubble-crop reading tied to a physical bubble must win dedupe over a
    # longer tile segment that merged the same words with artwork junk.
    if seg.bubble_detected:
        score += 30
    if seg.placement_confirmed:
        score += 10
    return score

def segments_score(segs: list[Segment]) -> float:
    return sum(segment_quality_score(s) for s in segs)

def dedupe_segments(segs: list[Segment]) -> list[Segment]:
    """Remove the same bubble seen twice in an overlap between tiles."""
    kept: list[Segment] = []
    for seg in sort_reading_order(segs):
        duplicate_at = None
        for idx, old in enumerate(kept):
            if box_iou(seg.box, old.box) >= 0.42:
                duplicate_at = idx; break
            if normalize(seg.text).lower() == normalize(old.text).lower():
                sx, sy = _center(seg.box); ox, oy = _center(old.box)
                allowed = max(36.0, 0.9 * max(seg.box[3], old.box[3]))
                if math.hypot(sx - ox, sy - oy) <= allowed:
                    duplicate_at = idx; break
        if duplicate_at is None:
            kept.append(seg)
        elif segment_quality_score(seg) > segment_quality_score(kept[duplicate_at]):
            kept[duplicate_at] = seg
    return sort_reading_order(kept)

def _contains_arabic(text: str) -> bool:
    return any("\u0600" <= ch <= "\u06ff" or "\u0750" <= ch <= "\u077f" or
               "\ufb50" <= ch <= "\ufdff" or "\ufe70" <= ch <= "\ufeff" for ch in str(text or ""))

def is_probable_ocr_junk(seg: Segment) -> bool:
    """Heuristic for letter-like artwork/SFX fragments (PY, ORBS, a, ps...).

    This is deliberately conservative for text already proven to sit inside a
    speech bubble: short real dialogue such as "No!" survives there, while a
    two-letter fragment floating on artwork does not get typeset onto a face.
    """
    text = normalize(seg.text)
    letters = [ch for ch in text if ch.isalpha()]
    if not letters:
        return True
    non_space = [ch for ch in text if not ch.isspace()]
    if len(letters) / max(1, len(non_space)) < 0.50:
        return True
    conf = 55.0 if seg.confidence is None else float(seg.confidence)
    if not seg.bubble_detected:
        if len(letters) <= 2:
            return True
        if len(letters) <= 3 and conf < 68:
            return True
        latin = all(ch.isascii() for ch in letters)
        joined = "".join(letters)
        if latin and joined.isupper() and len(joined) <= 4 and conf < 92:
            return True
        if latin and len(joined) >= 4 and not any(ch in "aeiouAEIOU" for ch in joined) and conf < 88:
            return True
    if len(set(letters)) == 1 and len(letters) >= 4 and conf < 90:
        return True
    return False

def filter_segments(segs: list[Segment]) -> list[Segment]:
    return [seg for seg in segs if not is_probable_ocr_junk(seg)]

def merge_same_bubble_segments(segs: list[Segment]) -> list[Segment]:
    """Force one typeset text per detected physical bubble.

    The bad live render stacked several OCR fragments into one green box;
    their Sorani lines then printed on top of each other. Segments that were
    assigned the same bubble contour are one dialogue, so merge them before
    translation instead of drawing them independently.
    """
    grouped: dict[object, list[Segment]] = {}
    order: list[object] = []
    for idx, seg in enumerate(sort_reading_order(segs)):
        key = ("bubble", seg.bubble_id) if seg.bubble_detected and seg.bubble_id is not None else ("single", idx)
        if key not in grouped:
            grouped[key] = []; order.append(key)
        grouped[key].append(seg)
    merged: list[Segment] = []
    for key in order:
        group = grouped[key]
        if len(group) == 1:
            merged.append(group[0]); continue
        group = sort_reading_order(group)
        chars = sum(max(1, len(seg.text)) for seg in group)
        weighted = [seg.confidence for seg in group if seg.confidence is not None]
        conf = (round(sum(seg.confidence * max(1, len(seg.text)) for seg in group
                          if seg.confidence is not None) /
                      max(1, sum(max(1, len(seg.text)) for seg in group
                                 if seg.confidence is not None)), 1)
                if weighted else None)
        render_boxes = [seg.render_box for seg in group if seg.render_box]
        merged.append(Segment(
            normalize(" ".join(seg.text for seg in group)),
            _union_box([seg.box for seg in group]),
            confidence=conf,
            render_box=_union_box(render_boxes) if render_boxes else None,
            bubble_detected=True,
            bubble_id=group[0].bubble_id,
            placement_confirmed=True,
        ))
    return sort_reading_order(merged)

def is_renderable_segment(seg: Segment) -> bool:
    """Safe to erase + typeset: proven bubble/flat placement, credible text."""
    if not (seg.bubble_detected or seg.placement_confirmed) or not seg.render_box or is_probable_ocr_junk(seg):
        return False
    if seg.confidence is not None and float(seg.confidence) < 45:
        return False
    return True

def renderable_segments(segs: list[Segment]) -> list[Segment]:
    return [seg for seg in segs if is_renderable_segment(seg)]

def make_tiles(width: int, height: int, tile_height: int | None = None,
               overlap: int | None = None) -> list[Tile]:
    """Overlapping horizontal slices. Each output owns only its central core."""
    if width <= 0 or height <= 0:
        return []
    if tile_height is None:
        tile_height = int(max(900, min(1600, width * 1.6 if width else 1200)))
    tile_height = max(300, int(tile_height))
    if overlap is None:
        overlap = int(max(120, min(320, tile_height * 0.22)))
    overlap = max(0, min(int(overlap), tile_height // 2))
    if height <= tile_height:
        return [Tile(0, height, 0, height)]
    tiles, y, step = [], 0, tile_height - overlap
    while y < height:
        y1 = min(height, y + tile_height)
        core_y0 = 0 if y == 0 else y + overlap // 2
        core_y1 = height if y1 == height else y1 - overlap // 2
        tiles.append(Tile(y, y1, core_y0, core_y1))
        if y1 == height:
            break
        y += step
    return tiles

def _segment_in_tile_core(seg: Segment, tile: Tile, is_last: bool) -> bool:
    cy = _center(seg.box)[1]
    return tile.core_y0 <= cy <= tile.core_y1 if is_last else tile.core_y0 <= cy < tile.core_y1

def _transform_segment(seg: Segment, dx: int, dy: int, scale: float) -> Segment:
    x, y, w, h = seg.box
    box = (int(round(x / scale + dx)), int(round(y / scale + dy)),
           max(1, int(round(w / scale))), max(1, int(round(h / scale))))
    return replace(seg, box=box, render_box=None, bubble_detected=False, bubble_id=None,
                   placement_confirmed=False)

def run_tesseract_tsv(image_path: Path, src: str, psm: int = 6, timeout: int = 180) -> str:
    lang = TESS_LANG.get(src, "eng")
    try:
        p = subprocess.run(["tesseract", str(image_path), "stdout", "-l", lang,
                            "--oem", "1", "--psm", str(psm), "tsv"],
                           capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError as e:
        raise OCRError("Tesseract نییە (بەخۆڕاییە): Ubuntu: sudo apt install tesseract-ocr | "
                       "Windows: https://github.com/UB-Mannheim/tesseract/wiki") from e
    except subprocess.TimeoutExpired as e:
        raise OCRError("Tesseract timeout") from e
    if p.returncode != 0:
        raise OCRError(f"tesseract: {p.stderr.strip()[:300]}")
    return p.stdout

def _ocr_tile_segments(tile_path: Path, src: str, min_conf: float = 35,
                       edge_margin_ratio: float = 0.0) -> list[Segment]:
    """Run uniform-block and sparse-text OCR, then merge at word level.

    PSM 6 is steadier inside a normal bubble; PSM 11 notices isolated bubbles
    scattered over artwork. Neither pass is trusted blindly: identical words
    are deduplicated before our own spatial line/bubble grouping runs.

    Bubble-interior crops can pass a stricter confidence floor and an edge
    margin: the printed outline at the crop perimeter otherwise OCRs as
    short junk tokens that survive translation as untranslated Latin and
    render as tofu boxes in an Arabic-only font.
    """
    words: list[dict] = []
    errors: list[str] = []
    for psm in (6, 11):
        try:
            words.extend(parse_tesseract_words(
                run_tesseract_tsv(tile_path, src, psm=psm), min_conf=min_conf))
        except OCRError as e:
            errors.append(str(e))
    if errors and not words:
        raise OCRError(errors[0])
    words = dedupe_words(words)
    if edge_margin_ratio and words:
        try:
            width, height = image_size(tile_path)
            mx = max(6.0, width * edge_margin_ratio)
            my = max(6.0, height * edge_margin_ratio)
            inside = [w for w in words
                      if mx <= _center(_word_box(w))[0] <= width - mx
                      and my <= _center(_word_box(w))[1] <= height - my]
            if inside:
                words = inside
        except Exception:
            pass
    return group_lines_into_segments(group_words_into_lines(words))

def image_size(path: Path) -> tuple[int, int]:
    from PIL import Image
    with Image.open(path) as img:
        return img.size

def _fallback_render_box(box, size):
    _x, _y, w, h = box
    return expand_box(box, max(10, int(w * 0.14)), max(8, int(h * 0.20)), size)

def _detect_bubble_candidates(gray, width, height, cv2, np) -> list[dict]:
    """Light-region contours that look like printed speech bubbles."""
    light = cv2.inRange(gray, 232, 255)
    kernel = np.ones((9, 9), np.uint8)
    light = cv2.morphologyEx(light, cv2.MORPH_CLOSE, kernel, iterations=1)
    contours, _ = cv2.findContours(light, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    candidates = []
    page_area = max(1, width * height)
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        bbox_area = max(1, w * h)
        contour_area = float(cv2.contourArea(contour))
        if w < 45 or h < 24 or contour_area < 1500 or bbox_area > page_area * 0.22:
            continue
        # A full-width white panel gap is page background, not dialogue.
        if x <= 2 and x + w >= width - 2:
            continue
        extent = contour_area / bbox_area
        # A long speech tail makes even a real oval's bbox mostly empty;
        # the live sample's true bubbles measure ~0.42-0.43 here.
        if extent < 0.38:
            continue
        hull_area = float(cv2.contourArea(cv2.convexHull(contour)))
        solidity = contour_area / max(1.0, hull_area)
        if solidity < 0.62:
            continue
        if not (0.22 <= w / max(1, h) <= 5.5):
            continue
        # Real printed bubbles have a mostly dark printed outline. White
        # clothes / blank panel space generally do not have a complete
        # dark ring immediately outside the light region. Test only a
        # crop around this contour so a 6000px strip stays fast.
        pad = 9
        rx0, ry0 = max(0, x - pad), max(0, y - pad)
        rx1, ry1 = min(width, x + w + pad), min(height, y + h + pad)
        local_mask = np.zeros((ry1 - ry0, rx1 - rx0), dtype=np.uint8)
        local_contour = contour - np.array([[[rx0, ry0]]], dtype=contour.dtype)
        cv2.drawContours(local_mask, [local_contour], -1, 255, -1)
        dilated = cv2.dilate(local_mask, np.ones((7, 7), np.uint8), iterations=1)
        ring = cv2.bitwise_and(dilated, cv2.bitwise_not(local_mask))
        ring_values = gray[ry0:ry1, rx0:rx1][ring > 0]
        if ring_values.size == 0:
            continue
        dark_ring_fraction = float(np.mean(ring_values < 115))
        if dark_ring_fraction < 0.30:
            continue
        # Isolate the bubble body from its narrow speech tail: body rows
        # are wide, tail rows are not. Typesetting into the whole
        # contour bbox (body + tail) put live text far too low.
        filled = np.zeros((h, w), dtype=np.uint8)
        cv2.drawContours(filled, [contour - np.array([[[x, y]]], dtype=contour.dtype)], -1, 255, -1)
        row_widths = (filled > 0).sum(axis=1)
        wide_rows = np.where(row_widths >= 0.42 * max(1, int(row_widths.max())))[0]
        if len(wide_rows) == 0:
            continue
        runs = []
        run_start = prev_row = int(wide_rows[0])
        for row in wide_rows[1:]:
            row = int(row)
            if row - prev_row > 3:
                runs.append((run_start, prev_row)); run_start = row
            prev_row = row
        runs.append((run_start, prev_row))
        body_y0, body_y1 = max(runs, key=lambda run: run[1] - run[0])
        body_xs = np.where(filled[body_y0:body_y1 + 1] > 0)[1]
        if len(body_xs) == 0:
            continue
        body_box = (x + int(body_xs.min()), y + int(body_y0),
                    int(body_xs.max() - body_xs.min() + 1), int(body_y1 - body_y0 + 1))
        if body_box[2] < 45 or body_box[3] < 24:
            continue
        candidates.append({
            "id": len(candidates) + 1,
            "box": body_box,
            "full_box": (x, y, w, h),
            "bbox_area": max(1, body_box[2] * body_box[3]),
            "contour_area": contour_area,
            "contour": contour,
            "extent": extent,
        })
    candidates.sort(key=lambda item: item["bbox_area"])
    return candidates

def _bubble_inner_box(cand: dict, seg_box=None, size=None):
    """Inscribed typesetting rectangle for a detected bubble body."""
    box = cand["box"]
    bx0, by0, _bx1, _by1 = _xyxy(box)
    ovalish = cand["extent"] < 0.87
    inner_w = max(8, int(box[2] * (0.72 if ovalish else 0.86)))
    inner_h = max(8, int(box[3] * (0.72 if ovalish else 0.84)))
    inner = (bx0 + (box[2] - inner_w) // 2,
             by0 + (box[3] - inner_h) // 2, inner_w, inner_h)
    if seg_box:
        return expand_box(_union_box([inner, seg_box]), 0, 0, size) if size else _union_box([inner, seg_box])
    return inner

def _point_in_contour(cv2, cand: dict, x: float, y: float) -> bool:
    try:
        return cv2.pointPolygonTest(cand["contour"], (float(x), float(y)), False) >= 0
    except Exception:
        return False

def assign_render_boxes(path: Path, segs: list[Segment], size: tuple[int, int] | None = None) -> list[Segment]:
    """Estimate a typesetting interior for each OCR segment.

    If OpenCV is installed, large light speech-bubble contours are used when
    a text segment clearly lives inside one. Otherwise the tight text box is
    modestly expanded. This is not a replacement for a trained detector, but
    it is far safer for reading order than pretending a whole page is a box.
    """
    if not segs:
        return segs
    try:
        size = size or image_size(path)
    except Exception:
        size = None
    for seg in segs:
        if not seg.render_box:
            seg.render_box = _fallback_render_box(seg.box, size) if size else seg.box
    try:
        import cv2
        import numpy as np
    except Exception:
        return segs
    try:
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if img is None:
            return segs
        height, width = img.shape[:2]
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        candidates = _detect_bubble_candidates(gray, width, height, cv2, np)
        for seg in segs:
            sx0, sy0, sx1, sy1 = _xyxy(seg.box)
            cx, cy = _center(seg.box)
            seg_area = max(1, (sx1 - sx0) * (sy1 - sy0))
            # Sample the inner 3x3 grid, not rectangle corners: in an oval
            # bubble the corners of a wide text line are legitimately near
            # or just outside the curved edge.
            sample_points = [(sx0 + (sx1 - sx0) * fx, sy0 + (sy1 - sy0) * fy)
                             for fy in (0.25, 0.50, 0.75) for fx in (0.25, 0.50, 0.75)]
            for cand in candidates:
                box = cand["box"]
                if cand["bbox_area"] < seg_area * 1.03 or seg_area / max(1.0, cand["contour_area"]) > 0.82:
                    continue
                bx0, by0, bx1, by1 = _xyxy(box)
                fx0, fy0, fx1, fy1 = _xyxy(cand["full_box"])
                contained = (_overlap(sx0, sx1, fx0, fx1) * _overlap(sy0, sy1, fy0, fy1)) / seg_area
                if contained < 0.65:
                    continue
                # Bounding-box overlap let white clothes "contain" text. The
                # words themselves must lie inside the actual bubble shape.
                inside = [cv2.pointPolygonTest(cand["contour"], (float(px), float(py)), False) >= 0
                          for px, py in sample_points]
                if cv2.pointPolygonTest(cand["contour"], (float(cx), float(cy)), False) < 0:
                    continue
                if sum(inside) / len(inside) < 0.62:
                    continue
                # Oval bubbles need an inscribed rectangle; squarer bubbles
                # can use more of their interior. This also stops the white
                # cleanup fill from painting square corners over artwork.
                seg.render_box = _bubble_inner_box(cand, seg.box, (width, height))
                seg.bubble_detected = True
                seg.bubble_id = int(cand["id"])
                seg.placement_confirmed = True
                break

        # Narration is often printed directly on a flat sky/colour wash with
        # no bubble outline. Such a segment may be typeset at its own tight
        # OCR location only when the ring around the letters is genuinely
        # flat; a face/hair/clothing texture fails this uniformity test.
        for seg in segs:
            if seg.bubble_detected or not seg.render_box:
                continue
            if seg.confidence is None or float(seg.confidence) < 70:
                continue
            if sum(ch.isalnum() for ch in seg.text) < 4:
                continue
            rx0, ry0, rx1, ry1 = _xyxy(seg.render_box)
            tx0, ty0, tx1, ty1 = _xyxy(expand_box(seg.box, 3, 3, (width, height)))
            crop = gray[max(0, ry0):min(height, ry1), max(0, rx0):min(width, rx1)].astype(np.float32)
            if crop.size < 200:
                continue
            mask = np.ones(crop.shape, dtype=bool)
            ly0, ly1 = max(0, ty0 - ry0), min(crop.shape[0], ty1 - ry0)
            lx0, lx1 = max(0, tx0 - rx0), min(crop.shape[1], tx1 - rx0)
            if ly1 > ly0 and lx1 > lx0:
                mask[ly0:ly1, lx0:lx1] = False
            background = crop[mask]
            if background.size < 80:
                continue
            median = float(np.median(background))
            uniform = float(np.mean(np.abs(background - median) <= 24))
            if uniform >= 0.72 and float(np.std(background)) <= 42:
                seg.placement_confirmed = True
        return merge_same_bubble_segments(segs)
    except Exception:
        pass
    return segs

def _ocr_uncovered_bubbles(img, existing: list[Segment], src: str, tempdir: Path) -> list[Segment]:
    """OCR each detected bubble interior that whole-tile OCR missed.

    The live page's clear top bubble ("WHAT'S THAT?") produced no tile
    segment at all, while its white oval is easy to detect. Cropping to the
    bubble and upscaling gives Tesseract the one job it is good at.
    """
    try:
        import cv2
        import numpy as np
        from PIL import Image, ImageOps
    except Exception:
        return []
    try:
        width, height = img.size
        gray = cv2.cvtColor(np.array(img), cv2.COLOR_RGB2GRAY)
        candidates = _detect_bubble_candidates(gray, width, height, cv2, np)
    except Exception:
        return []
    extra: list[Segment] = []
    for idx, cand in enumerate(candidates):
        fx0, fy0, fx1, fy1 = _xyxy(cand["full_box"])
        covered = False
        suspect: list[Segment] = []
        for seg in existing + extra:
            cx, cy = _center(seg.box)
            if not (fx0 - 4 <= cx <= fx1 + 4 and fy0 - 4 <= cy <= fy1 + 4):
                continue
            sx0, sy0, sx1, sy1 = _xyxy(seg.box)
            contained = (_overlap(sx0, sx1, fx0, fx1) * _overlap(sy0, sy1, fy0, fy1)) / max(1, (sx1 - sx0) * (sy1 - sy0))
            # A segment that spills from the bubble into hands/clothes (the
            # rejected page's "POISON EVASION ORB?" box) is not coverage: it
            # will fail placement. Only a cleanly contained box whose centre
            # is really inside the bubble *shape* covers this bubble; the
            # tail-inclusive bounding box also covers nearby artwork.
            if contained >= 0.90 and _point_in_contour(cv2, cand, cx, cy):
                covered = True; break
            if seg in existing and _point_in_contour(cv2, cand, cx, cy):
                suspect.append(seg)
        if covered:
            continue
        # Crop the bubble *body*, not the tail-inclusive contour bbox: the
        # tail column drags speakers' clothes/hands into the OCR crop and
        # re-creates the dirty grouping this pass exists to replace.
        bx0, by0, bx1, by1 = _xyxy(cand["box"])
        inset_x = max(2, int((bx1 - bx0) * 0.03)); inset_y = max(2, int((by1 - by0) * 0.03))
        cx0, cy0 = max(0, bx0 + inset_x), max(0, by0 + inset_y)
        cx1, cy1 = min(width, bx1 - inset_x), min(height, by1 - inset_y)
        if cx1 - cx0 < 30 or cy1 - cy0 < 16:
            continue
        crop = img.crop((cx0, cy0, cx1, cy1))
        # Whiten everything outside the bubble contour before OCR. The
        # rectangular body crop still contains the speaker's hand/clothes in
        # its corners (this bubble overlaps a body), and Tesseract grouped
        # those strokes with the dialogue, producing a box too dirty to
        # place. Inside the printed contour is bubble interior by definition.
        try:
            arr = cv2.cvtColor(np.array(crop), cv2.COLOR_RGB2BGR)
            mask = np.zeros(arr.shape[:2], dtype=np.uint8)
            local_contour = cand["contour"] - np.array([[[cx0, cy0]]], dtype=cand["contour"].dtype)
            cv2.drawContours(mask, [local_contour], -1, 255, -1)
            mask = cv2.erode(mask, np.ones((5, 5), np.uint8), iterations=1)
            if np.any(mask):
                arr[mask == 0] = (255, 255, 255)
                crop = Image.fromarray(cv2.cvtColor(arr, cv2.COLOR_BGR2RGB))
        except Exception:
            pass
        scale = 3.0 if crop.width < 320 else 2.0 if crop.width < 720 else 1.0
        if scale != 1.0:
            crop = crop.resize((int(crop.width * scale), int(crop.height * scale)), Image.LANCZOS)
        prepared = ImageOps.autocontrast(crop.convert("L"))
        crop_path = Path(tempdir) / f"bubble_{idx:03d}.png"
        prepared.save(crop_path)
        try:
            bubble_segs = _ocr_tile_segments(
                crop_path, src, min_conf=55, edge_margin_ratio=0.05)
        except OCRError:
            continue
        found_here: list[Segment] = []
        for seg in bubble_segs:
            full = _transform_segment(seg, cx0, cy0, scale)
            cx, cy = _center(full.box)
            if not (fx0 - 4 <= cx <= fx1 + 4 and fy0 - 4 <= cy <= fy1 + 4):
                continue
            if not _point_in_contour(cv2, cand, cx, cy):
                continue
            # This text was read from inside this physical contour, so tie it
            # to the bubble now. The generic assignment later rejects boxes
            # that fill most of a bubble (area ratio) — exactly what a tight
            # two-line reading of a small bubble looks like — and that left
            # this bubble in English even after the crop pass ran.
            full.bubble_detected = True
            full.bubble_id = int(cand["id"])
            full.placement_confirmed = True
            full.render_box = _bubble_inner_box(cand, None, (width, height))
            found_here.append(full)
        if found_here:
            # The bubble-crop reading is authoritative for this bubble;
            # drop the tile segment that spilled into the artwork so the
            # deduplicator cannot resurrect the dirty box.
            for bad in suspect:
                if bad in existing:
                    existing.remove(bad)
            extra.extend(found_here)
    return extra

def ocr_image(path: Path, src: str, tile_height: int | None = None,
              overlap: int | None = None) -> list[Segment]:
    """OCR an image in overlapping tiles and return full-page coordinates."""
    path = Path(path)
    try:
        from PIL import Image, ImageOps
    except ImportError:
        # Last-resort path for a text-only install; tiling needs Pillow.
        return parse_tesseract_tsv(run_tesseract_tsv(path, src))
    try:
        with Image.open(path) as opened:
            img = opened.convert("RGB")
    except Exception as e:
        raise OCRError(f"نەتوانرا وێنەکە بکرێتەوە: {e}") from e
    width, height = img.size
    tiles = make_tiles(width, height, tile_height, overlap)
    scale = 2.0 if width < 1200 else 1.0
    found: list[Segment] = []
    with tempfile.TemporaryDirectory(prefix="manhwa-tiles-") as td:
        for idx, tile in enumerate(tiles):
            crop = img.crop((0, tile.y0, width, tile.y1))
            if scale != 1.0:
                crop = crop.resize((int(width * scale), int((tile.y1 - tile.y0) * scale)), Image.LANCZOS)
            prepared = ImageOps.autocontrast(crop.convert("L"))
            tile_path = Path(td) / f"tile_{idx:03d}.png"
            prepared.save(tile_path)
            tile_segs = _ocr_tile_segments(tile_path, src)
            for seg in tile_segs:
                full = _transform_segment(seg, 0, tile.y0, scale)
                if len(tiles) == 1 or _segment_in_tile_core(full, tile, idx == len(tiles) - 1):
                    found.append(full)
        # Second chance for clear bubbles the tile pass missed entirely.
        found.extend(_ocr_uncovered_bubbles(img, found, src, Path(td)))
    segs = dedupe_segments(found)
    segs = assign_render_boxes(path, segs, (width, height))
    return filter_segments(segs)

def assess_segments(width: int, height: int, segs: list[Segment]) -> dict:
    """Quality gate: catch the old 'one giant segment for a tall strip' failure."""
    reasons: list[str] = []
    chars = sum(len(s.text.strip()) for s in segs)
    confidences = [s.confidence for s in segs if s.confidence is not None]
    avg_conf = round(sum(confidences) / len(confidences), 1) if confidences else None
    long_image = bool(width and height and (height >= 3 * width or height >= 3000))
    if not segs:
        reasons.append("no_text")
    if long_image and len(segs) < 2:
        reasons.append("too_few_segments_for_long_image")
    if long_image and chars < 12:
        reasons.append("too_little_text_for_long_image")
    if height and segs and max(s.box[3] for s in segs) / height > 0.35 and len(segs) <= 3:
        reasons.append("segments_look_merged")
    if avg_conf is not None and avg_conf < 42:
        reasons.append("low_ocr_confidence")
    if width and height and segs:
        covered = sum(s.box[2] * s.box[3] for s in segs) / max(1, width * height)
        if covered > 0.55:
            reasons.append("ocr_boxes_cover_too_much_page")
    return {"ok": not reasons, "reasons": reasons, "long_image": long_image,
            "segments": len(segs), "chars": chars, "avg_confidence": avg_conf}

def assess_render_plan(width: int, height: int, segs: list[Segment]) -> dict:
    """Placement gate, separate from OCR quantity/confidence.

    The rejected live sample had 19 OCR fragments, so the old count-based
    gate passed while text landed on faces, clothes and one shared box.
    A rendered page is allowed only when enough segments are tied to a
    physical bubble and their typesetting boxes do not collide.
    """
    reasons: list[str] = []
    placed = renderable_segments(segs)
    ratio = len(placed) / len(segs) if segs else 0.0
    if not segs:
        reasons.append("no_segments")
    if segs and not placed:
        reasons.append("no_bubble_backed_segments")
    if segs and ratio < 0.50:
        reasons.append("too_few_bubble_backed_segments")
    page_area = max(1, width * height) if width and height else 1
    for seg in placed:
        if seg.render_box and seg.render_box[2] * seg.render_box[3] / page_area > 0.22:
            reasons.append("render_box_too_large"); break
    overlap_found = False
    for i, a in enumerate(placed):
        for b in placed[i + 1:]:
            if a.render_box and b.render_box and box_iou(a.render_box, b.render_box) > 0.30:
                overlap_found = True; break
        if overlap_found:
            break
    if overlap_found:
        reasons.append("overlapping_render_boxes")
    return {"ok": not reasons, "reasons": reasons,
            "segments": len(segs), "renderable_segments": len(placed),
            "renderable_ratio": round(ratio, 3),
            "unique_bubbles": len({s.bubble_id for s in placed if s.bubble_id is not None})}

# ---------------------------------------------------------------- rendering (optional Pillow)
FONT_CANDIDATES = [os.environ.get("MANHWA_FONT", ""),
    "/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf",
    "/usr/share/fonts/truetype/noto/NotoSansArabic-Regular.ttf",
    "/usr/share/fonts/truetype/noto/NotoKufiArabic-Regular.ttf",
    "/usr/share/fonts/truetype/noto/NotoNaskhArabicUI-Regular.ttf",
    "/usr/share/fonts/truetype/noto/NotoSansArabicUI-Regular.ttf",
    "C:/Windows/Fonts/NotoNaskhArabic-Regular.ttf",
    "C:/Windows/Fonts/NotoSansArabic-Regular.ttf",
    "C:/Windows/Fonts/NotoKufiArabic-Regular.ttf"]

def find_sorani_font(candidates: list[str] | None = None) -> str | None:
    """Return an Arabic-script font only.

    The previous DejaVu/Arial fallback produced the separated, wrong-looking
    Sorani glyphs in the live sample. Refusing to render is better than
    silently typesetting Kurdish in a font that cannot shape it. A user may
    still explicitly choose another font with MANHWA_FONT.
    """
    for candidate in (FONT_CANDIDATES if candidates is None else candidates):
        if candidate and Path(candidate).exists():
            return str(candidate)
    return None

_RAQM_AVAILABLE: bool | None = None

def _raqm_available() -> bool:
    global _RAQM_AVAILABLE
    if _RAQM_AVAILABLE is None:
        try:
            from PIL import features
            _RAQM_AVAILABLE = bool(features.check("raqm"))
        except Exception:
            _RAQM_AVAILABLE = False
    return _RAQM_AVAILABLE

def shaping_backend() -> str:
    return "raqm" if _raqm_available() else "reshaper"

def require_text_shaping() -> None:
    if _raqm_available():
        return
    try:
        import arabic_reshaper  # noqa: F401
        from bidi.algorithm import get_display  # noqa: F401
    except ImportError as e:
        raise RuntimeError(
            "arabic_reshaper/python-bidi نییە؛ ڕێندەری کوردی ڕادەگیرێت "
            "بۆ ئەوەی پیتەکان جیا و هەڵە دەرنەکەون. "
            "Run: pip install -r manhwa/requirements.txt") from e

def _shape_with_native(text: str, native: bool) -> str:
    # Raqm/HarfBuzz consumes logical-order Unicode and does joining + bidi
    # itself. Feeding it reshaper output a second time was the live bug:
    # presentation-form glyphs got reordered and drawn letter-by-letter.
    if native:
        return text
    try:
        import arabic_reshaper
        from bidi.algorithm import get_display
        return get_display(arabic_reshaper.reshape(text))
    except ImportError:
        return text

def shape(text: str) -> str:
    return _shape_with_native(text, _raqm_available())

def _text_kwargs(text: str, native: bool | None = None) -> dict:
    native = _raqm_available() if native is None else native
    if native and _contains_arabic(text):
        return {"direction": "rtl", "language": "ckb"}
    return {}

def _measure_text(draw, text, font) -> float:
    return draw.textlength(shape(text), font=font, **_text_kwargs(text))

def _load_font(font_path, size, cache: dict):
    from PIL import ImageFont
    if not font_path:
        raise RuntimeError("Sorani font path is required for rendering")
    key = (font_path, size)
    if key not in cache:
        cache[key] = ImageFont.truetype(font_path, size)
    return cache[key]

def _glyph_signature(font, ch: str):
    mask = font.getmask(ch)
    return (mask.size, mask.getbbox(), bytes(mask), round(float(font.getlength(ch)), 3))

def _font_missing_chars(font_path: str, texts) -> set[str]:
    """Characters this font would draw as tofu (.notdef) boxes.

    Ubuntu's static Noto Arabic fonts contain no Latin letters at all, so a
    Latin token left over from OCR junk or an untranslated term rendered as
    the empty rectangles the user circled. Compare each glyph with a known
    unassigned codepoint instead of trusting the font's name.
    """
    import unicodedata
    unique = {ch for text in texts for ch in str(text or "")}
    font = _load_font(font_path, 32, {})
    sentinel_sig = _glyph_signature(font, "\U0010ffff")
    missing: set[str] = set()
    for ch in unique:
        if ch.isspace() or unicodedata.category(ch).startswith("C"):
            continue
        try:
            if ch == "\ufffd" or _glyph_signature(font, ch) == sentinel_sig:
                missing.add(ch)
        except Exception:
            continue
    return missing

def _choose_render_font(texts, candidates: list[str] | None = None):
    """Pick the candidate covering the most of the actual translated text."""
    paths: list[str] = []
    for candidate in (FONT_CANDIDATES if candidates is None else candidates):
        if candidate and Path(candidate).exists() and str(candidate) not in paths:
            paths.append(str(candidate))
    best = None
    for idx, path in enumerate(paths):
        try:
            missing = _font_missing_chars(path, texts)
        except Exception:
            continue
        key = (sum(1 for ch in missing if _contains_arabic(ch)), len(missing), idx)
        if best is None or key < best[0]:
            best = (key, path, missing)
    if best is not None:
        return best[1], best[2]
    return find_sorani_font(candidates), set()

_RENDER_PUNCT_REPLACEMENTS = {"?": "؟", ";": "؛", ",": "،", "…": "..."}

def _clean_render_text(text: str, missing: set[str]) -> str:
    """Remove glyphs the chosen font cannot draw (never typeset tofu)."""
    original = str(text or "").strip()
    if not missing:
        return original
    kept: list[str] = []
    for token in original.split():
        converted = "".join(
            _RENDER_PUNCT_REPLACEMENTS.get(ch, ch)
            if _RENDER_PUNCT_REPLACEMENTS.get(ch, ch) not in missing else ch
            for ch in token)
        had_missing_alnum = any(ch in missing and ch.isalnum() for ch in converted)
        cleaned = "".join(ch for ch in converted if ch not in missing)
        # A wholly unsupported token (e.g. leftover Latin "ORB?") goes away
        # with its punctuation instead of leaving a row of empty boxes.
        if had_missing_alnum and not any(ch.isalpha() and _contains_arabic(ch) for ch in cleaned):
            continue
        if cleaned:
            kept.append(cleaned)
    cleaned_text = " ".join(kept)
    cleaned_text = re.sub(r"\s+([؟،؛.!:])", r"\1", cleaned_text)
    cleaned_text = re.sub(r"([؟،؛.!:])(?:\s*\1)+", r"\1", cleaned_text)
    return normalize(cleaned_text)

def _split_long_word(draw, word, font, width):
    chunks, current = [], ""
    for ch in word:
        trial = current + ch
        if current and _measure_text(draw, trial, font) > width:
            chunks.append(current); current = ch
        else:
            current = trial
    if current:
        chunks.append(current)
    return chunks

def _wrap(draw, text, font, width):
    lines = []
    for paragraph in str(text or "").splitlines() or [""]:
        current = ""
        for word in paragraph.split():
            # Never split a Kurdish/Arabic word by characters: doing so breaks
            # letter joining at the split. _fit_font must shrink instead, and
            # if even the minimum size does not fit the segment is skipped.
            if _measure_text(draw, word, font) <= width or _contains_arabic(word):
                pieces = [word]
            else:
                pieces = _split_long_word(draw, word, font, width)
            for piece in pieces:
                trial = f"{current} {piece}".strip()
                if not current or _measure_text(draw, trial, font) <= width:
                    current = trial
                else:
                    lines.append(current); current = piece
        if current:
            lines.append(current)
    return lines

def _font_line_height(font, size) -> int:
    try:
        ascent, descent = font.getmetrics()
        return max(1, int(ascent + descent))
    except Exception:
        return max(1, int(size * 1.35))

def _layout_metrics(draw, lines, font, size):
    """Use full ascender+descender metrics; per-line ink boxes overlapped."""
    line_h = _font_line_height(font, size)
    gap = max(2, int(size * 0.12))
    total_h = len(lines) * line_h + max(0, len(lines) - 1) * gap
    max_w = max((_measure_text(draw, line, font) for line in lines), default=0)
    return total_h, line_h, gap, max_w

def _fit_font(draw, text, font_path, width, height, cache):
    """Largest fitting Sorani layout, or fits=False (never force overflow)."""
    width, height = max(12, int(width)), max(12, int(height))
    max_size = int(max(12, min(92, height * 0.78, width * 0.52)))
    min_size = 10
    best = None
    lo, hi = min_size, max_size
    while lo <= hi:
        size = (lo + hi) // 2
        font = _load_font(font_path, size, cache)
        lines = _wrap(draw, text, font, width)
        total_h, line_h, gap, max_w = _layout_metrics(draw, lines, font, size)
        if total_h <= height and max_w <= width:
            best = (font, size, lines, total_h, line_h, gap, True); lo = size + 1
        else:
            hi = size - 1
    if best:
        return best
    font = _load_font(font_path, min_size, cache)
    lines = _wrap(draw, text, font, width)
    total_h, line_h, gap, _max_w = _layout_metrics(draw, lines, font, min_size)
    return font, min_size, lines, total_h, line_h, gap, False

def _luminance(color):
    r, g, b = color[:3]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b

def _sample_background(img, box):
    """Dominant light background colour in/near a box (usually bubble white)."""
    x0, y0, x1, y1 = _xyxy(box)
    x0, y0 = max(0, x0), max(0, y0); x1, y1 = min(img.width, x1), min(img.height, y1)
    if x1 <= x0 or y1 <= y0:
        return (255, 255, 255)
    crop = img.crop((x0, y0, x1, y1))
    if crop.width * crop.height > 120_000:
        crop.thumbnail((360, 360))
    quantized = crop.quantize(colors=10, method=2)
    palette = quantized.getpalette() or []
    counts = sorted(quantized.getcolors() or [], reverse=True)
    colours = []
    for count, idx in counts:
        if idx * 3 + 2 < len(palette):
            colours.append((count, tuple(palette[idx * 3:idx * 3 + 3])))
    total = sum(count for count, _colour in colours) or 1
    for count, colour in colours:
        # Prefer bubble white only when it is a substantial part of the box.
        # The old "first light colour at any share" painted a fake white
        # rectangle over dark sky behind tiny white-outlined lettering.
        if _luminance(colour) >= 170 and count / total >= 0.25:
            return colour
    return colours[0][1] if colours else (255, 255, 255)

def _erase_original_text(img, segs):
    """Remove old lettering. OpenCV inpaints dark strokes; Pillow fallback fills."""
    from PIL import Image, ImageDraw
    size = img.size
    try:
        import cv2
        import numpy as np
    except Exception:
        draw = ImageDraw.Draw(img)
        for seg in segs:
            bg = _sample_background(img, seg.render_box or seg.box)
            erase = expand_box(seg.box, 5, 5, size)
            x0, y0, x1, y1 = _xyxy(erase)
            draw.rectangle([x0, y0, x1, y1], fill=bg)
        return img
    try:
        arr = cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)
        for seg in segs:
            erase = expand_box(seg.box, 5, 5, size)
            x0, y0, x1, y1 = _xyxy(erase)
            # A little context around the letters makes inpainting cleaner.
            mx0, my0 = max(0, x0 - 8), max(0, y0 - 8)
            mx1, my1 = min(size[0], x1 + 8), min(size[1], y1 + 8)
            crop = arr[my0:my1, mx0:mx1]
            if crop.size == 0:
                continue
            gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
            local = np.zeros(gray.shape, dtype=np.uint8)
            lx0, ly0, lx1, ly1 = x0 - mx0, y0 - my0, x1 - mx0, y1 - my0
            # Mask strokes by difference from the surrounding background,
            # not "dark pixels" only: white-outlined lettering on a dark
            # sky has light strokes that the old mask left behind.
            outer = np.ones(gray.shape, dtype=bool); outer[ly0:ly1, lx0:lx1] = False
            bg_median = float(np.median(gray[outer])) if np.any(outer) else float(np.median(gray))
            local[ly0:ly1, lx0:lx1] = np.where(
                np.abs(gray[ly0:ly1, lx0:lx1].astype(np.float32) - bg_median) > 42, 255, 0).astype(np.uint8)
            local = cv2.dilate(local, np.ones((3, 3), np.uint8), iterations=2)
            if np.any(local):
                arr[my0:my1, mx0:mx1] = cv2.inpaint(crop, local, 4, cv2.INPAINT_TELEA)
        out = Image.fromarray(cv2.cvtColor(arr, cv2.COLOR_BGR2RGB))
        # For a confidently detected uniform bubble, clear any faint remnants
        # inside the already-shrunk interior without drawing a fake outline.
        draw = ImageDraw.Draw(out)
        for seg in segs:
            if seg.bubble_detected and seg.render_box:
                bg = _sample_background(img, seg.render_box)
                if _luminance(bg) >= 180:
                    x0, y0, x1, y1 = _xyxy(seg.render_box)
                    radius = max(8, int(min(x1 - x0, y1 - y0) * 0.22))
                    draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=bg)
        return out
    except Exception:
        draw = ImageDraw.Draw(img)
        for seg in segs:
            bg = _sample_background(img, seg.render_box or seg.box)
            x0, y0, x1, y1 = _xyxy(expand_box(seg.box, 5, 5, size))
            draw.rectangle([x0, y0, x1, y1], fill=bg)
        return img

def render_image(inp: Path, segs: list[Segment], out: Path, render_report: dict | None = None):
    from PIL import Image, ImageDraw   # lazy: text mode works without Pillow
    inp, out = Path(inp), Path(out)
    require_text_shaping()
    font_path, missing_glyphs = _choose_render_font([seg.ckb for seg in segs])
    if not font_path:
        raise RuntimeError(
            "فۆنتی گونجاوی سۆرانی نەدۆزرایەوە؛ ڕێندەر ڕاگیرا بۆ ئەوەی پیتەکان "
            "جیا دەرنەکەون. Ubuntu: sudo apt install fonts-noto-core، "
            "یان MANHWA_FONT بۆ فۆنتێکی عەرەبی/کوردی دیاری بکە.")
    with Image.open(inp) as opened:
        img = opened.convert("RGB")
    for seg in segs:
        if not seg.render_box:
            seg.render_box = _fallback_render_box(seg.box, img.size)

    # Decide every layout BEFORE erasing anything. If Sorani cannot fit a
    # bubble at the minimum legible size, leave that bubble untouched rather
    # than erasing the source and printing an overlapping overflow.
    measure = ImageDraw.Draw(img)
    font_cache: dict = {}
    layouts = []
    cleaned_segments = 0
    for seg in segs:
        original_text = (seg.ckb or "").strip()
        text = _clean_render_text(original_text, missing_glyphs)
        if text != original_text:
            cleaned_segments += 1
        if not text:
            continue
        x, y, w, h = seg.render_box or seg.box
        pad = max(5, int(min(w, h) * 0.09))
        inner_w, inner_h = max(12, w - 2 * pad), max(12, h - 2 * pad)
        font, _size, lines, total_h, line_h, gap, fits = _fit_font(
            measure, text, font_path, inner_w, inner_h, font_cache)
        if fits:
            layouts.append((seg, font, lines, total_h, line_h, gap, (x, y, w, h)))

    img = _erase_original_text(img, [layout[0] for layout in layouts])
    draw = ImageDraw.Draw(img)
    for seg, font, lines, total_h, line_h, gap, (x, y, w, h) in layouts:
        bg = _sample_background(img, seg.render_box or seg.box)
        ink = (255, 255, 255) if _luminance(bg) < 95 else (0, 0, 0)
        ty = y + max(0, (h - total_h) // 2)
        for line in lines:
            # "ma" = horizontally centred at the font ascender. Combined
            # with ascent+descent line metrics, lines cannot pile together.
            draw.text((x + w / 2, ty), shape(line), fill=ink, font=font, anchor="ma", **_text_kwargs(line))
            ty += line_h + gap
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.suffix.lower() in {".jpg", ".jpeg"}:
        img.save(out, quality=92, subsampling=1)
    else:
        img.save(out)
    if render_report is not None:
        render_report.update({"requested": len(segs), "rendered": len(layouts),
                              "shaping": shaping_backend(), "font": font_path,
                              "missing_codepoints": [f"U+{ord(ch):04X}" for ch in sorted(missing_glyphs)],
                              "cleaned_segments": cleaned_segments})
    return out

def render_debug_image(inp: Path, segs: list[Segment], out: Path):
    """Diagnostic overlay: red=tight OCR box, green=typesetting area."""
    from PIL import Image, ImageDraw, ImageFont
    inp, out = Path(inp), Path(out)
    with Image.open(inp) as opened:
        img = opened.convert("RGB")
    draw = ImageDraw.Draw(img)
    font = ImageFont.load_default()
    for i, seg in enumerate(segs, 1):
        # Red = tight OCR. Green = proven bubble. Orange = flat narration
        # placement. Grey = not safe to typeset.
        render_colour = ((0, 180, 0) if seg.bubble_detected else
                         (255, 140, 0) if seg.placement_confirmed else (130, 130, 130))
        for box, colour in ((seg.box, (255, 0, 0)), (seg.render_box, render_colour)):
            if box:
                x0, y0, x1, y1 = _xyxy(box)
                draw.rectangle([x0, y0, x1, y1], outline=colour, width=max(2, img.width // 400))
        x, y, _w, _h = seg.box
        draw.text((x + 3, max(0, y - 16)), str(i), fill=(255, 0, 0), font=font)
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, quality=90) if out.suffix.lower() in {".jpg", ".jpeg"} else img.save(out)
    return out

# ---------------------------------------------------------------- outputs / CLI
def write_side_by_side(pairs: list[tuple[str, str]], out_txt: Path):
    lines = [f"[{i}]\nEN : {en}\nCKB: {ck}\n" for i, (en, ck) in enumerate(pairs, 1)]
    out_txt.write_text("\n".join(lines), encoding="utf-8")

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Manhwa/manga -> Kurdish Sorani (free, no API key)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--text"); g.add_argument("--file"); g.add_argument("--image"); g.add_argument("--segments-json")
    ap.add_argument("--provider", default="google-free",
                    help="google-free | mymemory | nllb | mock — or a chain: google-free,mymemory,nllb")
    ap.add_argument("--src", default="en"); ap.add_argument("--tgt", default="ckb")
    ap.add_argument("--outdir", default="out"); ap.add_argument("--render", action="store_true")
    ap.add_argument("--debug", action="store_true", help="also write an OCR box overlay image")
    ap.add_argument("--tile-height", type=int, default=None, help="OCR slice height for tall strips")
    ap.add_argument("--overlap", type=int, default=None, help="overlap between OCR slices")
    ap.add_argument("--cache", default=str(Path(__file__).parent / "cache.sqlite"))
    ap.add_argument("--glossary", default=str(Path(__file__).parent / "glossary.json"))
    ap.add_argument("--delay", type=float, default=0.4)
    a = ap.parse_args(argv)

    cache = TranslationCache(a.cache)
    tr = Translator(providers=[p.strip() for p in a.provider.split(",")], src=a.src, tgt=a.tgt,
                    cache=cache, glossary=load_glossary(a.glossary), delay=a.delay)
    outdir = Path(a.outdir); outdir.mkdir(parents=True, exist_ok=True)

    if a.text:
        print(tr.translate_one(a.text))
    elif a.file:
        raw = [normalize(l) for l in Path(a.file).read_text(encoding="utf-8").splitlines() if normalize(l)]
        pairs = list(zip(raw, tr.translate_many(raw)))
        out_txt = outdir / (Path(a.file).stem + ".ckb.txt")
        write_side_by_side(pairs, out_txt)
        print("\n".join(f"{en}\n→ {ck}\n" for en, ck in pairs)); print(f"✅ {out_txt}")
    else:
        qa = None; render_qa = None
        if a.image:
            img_path = Path(a.image); stem = img_path.stem
            try:
                segs = ocr_image(img_path, a.src, a.tile_height, a.overlap)
            except OCRError as e:
                sys.exit(f"❌ {e}")
            if not segs: sys.exit("❌ OCR هیچ دەقێکی نەدۆزیەوە — وێنەکە ڕووناک نییە یان فونتەکە تایبەتە.")
            try:
                dims = image_size(img_path)
                qa = assess_segments(*dims, segs)
                render_qa = assess_render_plan(*dims, segs)
            except Exception:
                qa = None; render_qa = None
            if qa and not qa["ok"]:
                print(f"⚠️ OCR quality gate: {qa} — inspect the JSON/debug image before trusting it.",
                      file=sys.stderr)
            if render_qa and not render_qa["ok"]:
                print(f"⚠️ Placement gate: {render_qa} — unsafe segments will not be typeset.",
                      file=sys.stderr)
        else:  # manual/known boxes: [{"text": "...", "box": [x,y,w,h], "image": "page.png"}]
            data = json.loads(Path(a.segments_json).read_text(encoding="utf-8"))
            segs = [Segment(s["text"], tuple(s["box"]), ckb=s.get("ckb", ""),
                            confidence=s.get("confidence"),
                            render_box=tuple(s["render_box"]) if s.get("render_box") else tuple(s["box"]),
                            bubble_detected=bool(s.get("bubble_detected", False)),
                            bubble_id=s.get("bubble_id"),
                            placement_confirmed=bool(s.get("placement_confirmed", s.get("bubble_detected", False))))
                    for s in data["segments"]]
            img_path = Path(data["image"])
            if not img_path.exists():  # allow paths relative to the JSON file
                img_path = Path(a.segments_json).parent / img_path
            stem = img_path.stem
            segs = assign_render_boxes(img_path, segs)
            try:
                render_qa = assess_render_plan(*image_size(img_path), segs)
            except Exception:
                render_qa = None
        for s, ck in zip(segs, tr.translate_many([s.text for s in segs])): s.ckb = ck
        write_side_by_side([(s.text, s.ckb) for s in segs], outdir / f"{stem}.ckb.txt")
        payload = {"segments": [{"en": s.text, "ckb": s.ckb, "box": s.box,
                                  "render_box": s.render_box, "confidence": s.confidence,
                                  "bubble_detected": s.bubble_detected, "bubble_id": s.bubble_id,
                                  "placement_confirmed": s.placement_confirmed,
                                  "renderable": is_renderable_segment(s)} for s in segs],
                   "qa": qa, "render_qa": render_qa}
        (outdir / f"{stem}.ckb.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"✅ {len(segs)} bubbles -> {outdir}/{stem}.ckb.txt + .json")
        if a.debug: print(f"🔎 {render_debug_image(img_path, segs, outdir / f'{stem}.debug.png')}")
        if a.render:
            # OCR mode is strict: artwork fragments are listed in JSON/text
            # but never typeset. Explicit manual boxes remain renderable.
            to_render = renderable_segments(segs) if a.image else segs
            if to_render:
                try:
                    rendered_path = render_image(img_path, to_render, outdir / f'{stem}.ckb.png')
                except RuntimeError as e:
                    sys.exit(f"❌ {e}")
                print(f"✅ {rendered_path} ({len(to_render)}/{len(segs)} segments placed)")
            else:
                print("⚠️ No bubble-backed segment was safe to typeset; rendered image skipped.",
                      file=sys.stderr)
    print(f"💾 cache: {cache.stats()} (reused = free, no API burn)", file=sys.stderr)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
