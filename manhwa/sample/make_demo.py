#!/usr/bin/env python3
"""Makes a FAKE English manhwa page (our own lines) to demo the translator.
Usage: python make_demo.py   ->  demo_en.png + demo_segments.json
Then:  python ../translate_manhwa.py --segments-json demo_segments.json --render --provider mock --outdir .
"""
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
BUBBLES = [
    ("I finally returned after ten years.", (40, 40, 430, 95)),
    ("Who are you?!", (330, 250, 300, 80)),
    ("Run! The monsters are coming!", (50, 430, 430, 95)),
    ("I will protect you!", (300, 620, 340, 80)),
]

img = Image.new("RGB", (720, 780), (38, 42, 58))
d = ImageDraw.Draw(img)
# two simple "panel" areas + silhouettes, just for flavour
d.rectangle([20, 20, 700, 370], outline=(12, 12, 16), width=4)
d.rectangle([20, 390, 700, 760], outline=(12, 12, 16), width=4)
d.ellipse([500, 90, 640, 230], fill=(16, 16, 20))
d.ellipse([90, 560, 220, 690], fill=(16, 16, 20))

segs = []
for text, (x, y, w, h) in BUBBLES:
    d.rounded_rectangle([x, y, x + w, y + h], radius=16, fill="white", outline="black", width=3)
    size = 24
    while size > 12:
        f = ImageFont.truetype(FONT, size)
        if d.textlength(text, font=f) <= w - 24: break
        size -= 2
    tw = d.textlength(text, font=f)
    tx, ty = x + (w - tw) / 2, y + (h - size) / 2
    d.text((tx, ty), text, fill="black", font=f)
    # box = tight English lettering; render_box = the whole speech bubble.
    segs.append({"text": text, "box": [int(tx), int(ty), int(tw), int(size)],
                 "render_box": [x, y, w, h], "bubble_detected": True})

img.save(HERE / "demo_en.png")
(HERE / "demo_segments.json").write_text(json.dumps(
    {"image": "demo_en.png", "segments": segs}, ensure_ascii=False, indent=2), encoding="utf-8")
print("demo_en.png + demo_segments.json")
