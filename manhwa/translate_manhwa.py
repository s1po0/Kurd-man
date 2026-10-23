#!/usr/bin/env python3
"""
Manhwa / manga -> Kurdish Sorani (ckb) translator.  100% free, NO paid API key.

Pipeline:  image --Tesseract OCR(free)--> source text --translate--> Sorani
           -> .txt / .json  (+ optional translated image with --render)

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
  Ubuntu/Debian: sudo apt install tesseract-ocr fonts-Noto-Naskh-arabic
  Windows:       https://github.com/UB-Mannheim/tesseract/wiki
Only translate material you have the rights to / for personal use.
"""
from __future__ import annotations

import argparse, csv, io, json, os, re, sqlite3, subprocess, sys, time, urllib.parse, urllib.request
from dataclasses import dataclass, field
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
        self.tok = AutoTokenizer.Aster_pretrained(model) if False else None
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
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

@dataclass
class Segment:
    text: str; box: tuple[int, int, int, int]  # x, y, w, h
    ckb: str = ""

def parse_tesseract_tsv(tsv: str, min_conf: int = 40) -> list[Segment]:
    """Group OCR words by block+paragraph (~ one speech bubble)."""
    groups: dict[tuple, list[dict]] = {}
    for row in csv.DictReader(io.StringIO(tsv), delimiter="\t", quoting=csv.QUOTE_NONE):
        word = normalize(row.get("text", ""))
        try: conf = float(row.get("conf", -1))
        except (TypeError, ValueError): conf = -1
        if not word or conf < min_conf: continue
        groups.setdefault((row.get("block_num"), row.get("par_num")), []).append(row | {"_w": word})
    segs = []
    for rows in groups.values():
        rows.sort(key=lambda r: (int(r["line_num"]), int(r["word_num"])))
        x = min(int(r["left"]) for r in rows); y = min(int(r["top"]) for r in rows)
        x2 = max(int(r["left"]) + int(r["width"]) for r in rows)
        y2 = max(int(r["top"]) + int(r["height"]) for r in rows)
        segs.append(Segment(" ".join(r["_w"] for r in rows), (x, y, x2 - x, y2 - y)))
    return sorted(segs, key=lambda s: (s.box[1], s.box[0]))

def ocr_image(path: Path, src: str) -> list[Segment]:
    lang = TESS_LANG.get(src, "eng")
    try:
        p = subprocess.run(["tesseract", str(path), "stdout", "-l", lang, "--psm", "6", "tsv"],
                           capture_output=True, text=True, timeout=120)
    except FileNotFoundError:
        sys.exit("❌ Tesseract نییە (بەخۆڕاییە): Ubuntu: sudo apt install tesseract-ocr | "
                 "Windows: https://github.com/UB-Mannheim/tesseract/wiki")
    if p.returncode != 0: sys.exit(f"❌ tesseract: {p.stderr.strip()[:300]}")
    return parse_tesseract_tsv(p.stdout)

# ---------------------------------------------------------------- rendering (optional Pillow)
FONT_CANDIDATES = [os.environ.get("MANHWA_FONT", ""),
    "/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf",
    "/usr/share/fonts/truetype/Noto/NotoNaskhArabic-Regular.ttf",
    "C:/Windows/Fonts/NotoNaskhArabic-Regular.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "C:/Windows/Fonts/arial.ttf"]

def shape(text: str) -> str:
    try:
        import arabic_reshaper
        from bidi.algorithm import get_display
        return get_display(arabic_reshaper.reshape(text))
    except ImportError:
        return text

def _wrap(draw, text, font, width):
    lines, cur = [], ""
    for w in text.split():
        trial = f"{cur} {w}".strip()
        if draw.textlength(shape(trial), font=font) <= width or not cur: cur = trial
        else: lines.append(cur); cur = w
    if cur: lines.append(cur)
    return lines

def render_image(inp: Path, segs: list[Segment], out: Path):
    from PIL import Image, ImageDraw, ImageFont   # lazy: text mode works without Pillow
    font_path = next((p for p in FONT_CANDIDATES if p and Path(p).exists()), None)
    img = Image.open(inp).convert("RGB"); d = ImageDraw.Draw(img)
    for s in segs:
        x, y, w, h = s.box; x, y = max(0, x - 6), max(0, y - 6); w, h = w + 12, h + 12
        d.rounded_rectangle([x, y, x + w, y + h], radius=10, fill="white", outline="black", width=2)
        size, lines = 26, []
        while size >= 11:
            font = ImageFont.truetype(font_path, size) if font_path else ImageFont.load_default()
            lines = _wrap(d, s.ckb, font, w - 14)
            if len(lines) * (size + 5) <= h - 8: break
            size -= 2
        ty = y + max(4, (h - len(lines) * (size + 5)) // 2)
        for line in lines:
            sh = shape(line); tw = d.textlength(sh, font=font)
            d.text((x + (w - tw) / 2, ty), sh, fill="black", font=font); ty += size + 5
    img.save(out); return out

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
        if a.image:
            segs, stem, img_path = ocr_image(Path(a.image), a.src), Path(a.image).stem, Path(a.image)
            if not segs: sys.exit("❌ OCR هیچ دەقێکی نەدۆزیەوە — وێنەکە ڕووناک نییە یان فونتەکە تایبەتە.")
        else:  # manual/known boxes: [{"text": "...", "box": [x,y,w,h], "image": "page.png"}]
            data = json.loads(Path(a.segments_json).read_text(encoding="utf-8"))
            segs = [Segment(s["text"], tuple(s["box"])) for s in data["segments"]]
            img_path = Path(data["image"])
            if not img_path.exists():  # allow paths relative to the JSON file
                img_path = Path(a.segments_json).parent / img_path
            stem = img_path.stem
        for s, ck in zip(segs, tr.translate_many([s.text for s in segs])): s.ckb = ck
        write_side_by_side([(s.text, s.ckb) for s in segs], outdir / f"{stem}.ckb.txt")
        (outdir / f"{stem}.ckb.json").write_text(json.dumps(
            [{"en": s.text, "ckb": s.ckb, "box": s.box} for s in segs], ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"✅ {len(segs)} bubbles -> {outdir}/{stem}.ckb.txt + .json")
        if a.render: print(f"✅ {render_image(img_path, segs, outdir / f'{stem}.ckb.png')}")
    print(f"💾 cache: {cache.stats()} (reused = free, no API burn)", file=sys.stderr)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
