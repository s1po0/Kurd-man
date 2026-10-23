#!/usr/bin/env python3
"""
One-pass Telegram inbox processor for the manhwa translator bot.

Why this exists:
  A Telegram bot only replies while *something* is polling Telegram for it.
  Our long-polling bot (bot/telegram-bot.mjs) needs a 24/7 machine.  Until
  the owner has one, GitHub Actions runs THIS script for one pass: it
  fetches messages already queued on Telegram's servers (kept ~24h),
  answers them, and exits.

What it handles (admin only):
  /start, /help   -> instructions
  /ping           -> pong
  any text        -> translated to Kurdish Sorani (google-free, cached)
  photo / image   -> tiled OCR (EN/KO) -> quality gate -> translated bubbles
                     -> rendered photo/document + side-by-side text back

Env (GitHub Secrets locally exported): TELEGRAM_BOT_TOKEN, TELEGRAM_ADMIN_ID
State: BOT_STATE_FILE JSON {"next_offset": int, "processed": [update_ids]}
       (kept between Actions runs via actions/cache; never committed)
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API_ROOT = "https://api.telegram.org"

# ---------------------------------------------------------------- pure helpers
def chunk_message(text: str, limit: int = 3900) -> list[str]:
    """Telegram sendMessage hard limit is 4096 chars; split on lines."""
    if len(text) <= limit:
        return [text] if text else []
    chunks, buf = [], ""
    for line in text.splitlines(keepends=True):
        while len(line) > limit:  # single monster line
            if buf:
                chunks.append(buf); buf = ""
            chunks.append(line[:limit]); line = line[limit:]
        if len(buf) + len(line) > limit and buf:
            chunks.append(buf); buf = line
        else:
            buf += line
    if buf:
        chunks.append(buf)
    return chunks

def pick_largest_photo(photos: list[dict]) -> dict | None:
    if not photos:
        return None
    return max(photos, key=lambda p: (p.get("file_size") or 0,
                                      (p.get("width") or 0) * (p.get("height") or 0)))

def classify_message(msg: dict) -> str:
    if msg.get("photo"):
        return "photo"
    doc = msg.get("document") or {}
    if str(doc.get("mime_type", "")).startswith("image/"):
        return "photo"
    text = (msg.get("text") or "").strip()
    if text.startswith("/"):
        return "command"
    if text:
        return "text"
    return "other"

def image_file_id(msg: dict) -> str | None:
    if msg.get("photo"):
        p = pick_largest_photo(msg["photo"])
        return p.get("file_id") if p else None
    doc = msg.get("document") or {}
    return doc.get("file_id")

def choose_send_method(width: int = 0, height: int = 0, file_size: int = 0) -> str:
    """Telegram photo upload squeezes/scale-limits tall strips; use files."""
    if height > 2500 or (width + height) > 9500 or file_size > 9_500_000:
        return "document"
    return "photo"

def lang_hint(msg: dict) -> str:
    caption = f"{msg.get('caption', '')} {msg.get('text', '')}".lower()
    if any(w in caption for w in ("ko", "korean", "کۆری", "한국")):
        return "ko"
    return "en"

def help_text() -> str:
    return (
        "📚 سڵاو! من بۆتی وەرگێڕانی مانهوام بۆ کوردی سۆرانی.\n\n"
        "📷 وێنەی یەک لاپەڕەی مانهوا بنێرە (ئینگلیزی یان کۆری) — "
        "بۆ وێنەی درێژ، لە تلگرام وەک File/Document بینێرە نەک وەک وێنەی پەستێنراو؛ "
        "وێنەکەت پارچەپارچە دەخوێنمەوە و وەردەگێڕم.\n"
        "✍️ یان هەر دەقێکی ئینگلیزی بنووسە، دەیکەم بە سۆرانی.\n\n"
        "⏳ تێبینی: بۆتەکە ئۆتۆماتیکی هەر ١٠ خولەک جارێک دەپشکنێت؛ وەڵام بەزۆری لە ٥ تا ٢٠ خولەکدا دەگات. پێویست ناکات لە شوێنێکی تر قسە بکەیت.\n"
        "/ping بۆ تاقیکردنەوە."
    )

@dataclass
class State:
    next_offset: int = 0
    processed: list[int] = field(default_factory=list)
    attempts: dict[str, int] = field(default_factory=dict)  # failed update_id -> tries

    @classmethod
    def load(cls, path: Path) -> "State":
        try:
            d = json.loads(path.read_text(encoding="utf-8"))
            return cls(int(d.get("next_offset", 0)), [int(x) for x in d.get("processed", [])],
                       {str(k): int(v) for k, v in (d.get("attempts") or {}).items()})
        except Exception:
            return cls()

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"next_offset": self.next_offset,
                                   "processed": self.processed[-500:],
                                   "attempts": self.attempts}), encoding="utf-8")
        tmp.replace(path)

    def mark(self, update_id: int) -> None:
        if update_id not in self.processed:
            self.processed.append(update_id)
        self.attempts.pop(str(update_id), None)
        self.next_offset = max(self.next_offset, update_id + 1)

    def fail(self, update_id: int, max_attempts: int = 3) -> bool:
        """Record a failure. Returns True when attempts are exhausted (give up)."""
        n = self.attempts.get(str(update_id), 0) + 1
        self.attempts[str(update_id)] = n
        if n >= max_attempts:
            self.mark(update_id)
            return True
        return False

# ---------------------------------------------------------------- Telegram API
class TelegramError(RuntimeError):
    pass

class TelegramClient:
    def __init__(self, token: str):
        if not token:
            raise TelegramError("TELEGRAM_BOT_TOKEN missing")
        self.base = f"{API_ROOT}/bot{token}"
        self.file_base = f"{API_ROOT}/file/bot{token}"

    def api(self, method: str, payload: dict | None = None, timeout: int = 30):
        data = json.dumps(payload or {}).encode()
        req = urllib.request.Request(f"{self.base}/{method}", data=data,
                                     headers={"content-type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                out = json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="ignore")[:300]
            raise TelegramError(f"{method} HTTP {e.code}: {detail}") from e
        if not out.get("ok"):
            raise TelegramError(f"{method}: {out.get('description')}")
        return out["result"]

    def get_updates(self, offset: int = 0) -> list[dict]:
        payload: dict = {"timeout": 0, "allowed_updates": ["message"]}
        if offset:
            payload["offset"] = offset
        return self.api("getUpdates", payload)

    def send_message(self, chat_id, text: str, reply_to: int | None = None) -> list[dict]:
        sent = []
        for i, part in enumerate(chunk_message(text)):
            payload = {"chat_id": chat_id, "text": part}
            if reply_to and i == 0:
                payload["reply_to_message_id"] = reply_to
            sent.append(self.api("sendMessage", payload))
        return sent

    def download(self, file_id: str, dest: Path) -> Path:
        info = self.api("getFile", {"file_id": file_id})
        dest.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(f"{self.file_base}/{info['file_path']}", timeout=60) as r:
            dest.write_bytes(r.read())
        return dest

    def _send_file(self, method: str, field: str, chat_id, path: Path,
                   caption: str = "", reply_to: int | None = None) -> dict:
        boundary = f"----manhwa{uuid.uuid4().hex}"
        fields = {"chat_id": str(chat_id), "caption": caption[:1024]}
        if reply_to:
            fields["reply_to_message_id"] = str(reply_to)
        body = b""
        for k, v in fields.items():
            body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n").encode()
        suffix = path.suffix.lower()
        mime = {"png": "image/png", "webp": "image/webp"}.get(suffix.lstrip("."), "image/jpeg")
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; "
                 f"filename=\"{path.name}\"\r\nContent-Type: {mime}\r\n\r\n").encode()
        body += path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(f"{self.base}/{method}", data=body, headers={
            "content-type": f"multipart/form-data; boundary={boundary}"})
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                out = json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            raise TelegramError(f"{method} HTTP {e.code}: {e.read().decode(errors='ignore')[:300]}") from e
        if not out.get("ok"):
            raise TelegramError(f"{method}: {out.get('description')}")
        return out["result"]

    def send_photo(self, chat_id, photo: Path, caption: str = "", reply_to: int | None = None) -> dict:
        return self._send_file("sendPhoto", "photo", chat_id, photo, caption, reply_to)

    def send_document(self, chat_id, document: Path, caption: str = "", reply_to: int | None = None) -> dict:
        return self._send_file("sendDocument", "document", chat_id, document, caption, reply_to)

# ---------------------------------------------------------------- translation reuse
def load_manhwa_module():
    spec = importlib.util.spec_from_file_location("translate_manhwa", ROOT / "manhwa" / "translate_manhwa.py")
    mod = importlib.util.module_from_spec(spec)
    # dataclasses + `from __future__ import annotations` resolve the defining
    # module via sys.modules; without this line exec_module dies with
    # AttributeError: 'NoneType' object has no attribute '__dict__'.
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod

class TranslatorFactory:
    def __init__(self):
        self.tm = load_manhwa_module()
        self.cache = self.tm.TranslationCache(ROOT / "manhwa" / "cache.sqlite")
        self.glossary = self.tm.load_glossary(ROOT / "manhwa" / "glossary.json")
        self._by_src: dict[str, object] = {}

    def get(self, src: str):
        if src not in self._by_src:
            providers = ["google-free"] if src == "auto" else ["google-free", "mymemory"]
            self._by_src[src] = self.tm.Translator(
                providers=providers, src=src, tgt="ckb",
                cache=self.cache, glossary=self.glossary, delay=0.25)
        return self._by_src[src]

# ---------------------------------------------------------------- processing
class InboxProcessor:
    def __init__(self, client: TelegramClient, admin_id: str, outdir: Path):
        self.client, self.admin_id, self.outdir = client, str(admin_id or ""), outdir
        self._factory: TranslatorFactory | None = None
        self.stats = {"updates": 0, "photos": 0, "texts": 0, "commands": 0,
                      "segments": 0, "rendered_segments": 0, "photo_replies": 0,
                      "document_replies": 0, "quality_rejected": 0,
                      "render_rejected": 0, "errors": 0}

    @property
    def factory(self) -> TranslatorFactory:
        if self._factory is None:
            self._factory = TranslatorFactory()
        return self._factory

    def process(self, update: dict) -> None:
        msg = update.get("message") or {}
        chat_id = (msg.get("chat") or {}).get("id")
        sender = str((msg.get("from") or {}).get("id", ""))
        if not chat_id:
            return
        if self.admin_id and sender != self.admin_id:
            self.client.send_message(chat_id, "⛔ ئەم بۆتە تایبەتە بە خاوەنەکەیەتی.")
            return
        kind = classify_message(msg)
        if kind == "command":
            self.stats["commands"] += 1
            cmd = (msg.get("text") or "").strip().split()[0].split("@")[0].lower()
            reply = "🏓 pong" if cmd == "/ping" else help_text()
            self.client.send_message(chat_id, reply, msg.get("message_id"))
        elif kind == "text":
            self.stats["texts"] += 1
            out = self.factory.get("auto").translate_one(msg.get("text", ""))
            self.client.send_message(chat_id, out, msg.get("message_id"))
        elif kind == "photo":
            self._process_photo(msg, chat_id)
        else:
            self.client.send_message(chat_id, "📷 تکایە وێنەی لاپەڕە یان دەقی ئینگلیزی بنێرە. /help",
                                     msg.get("message_id"))

    def _process_photo(self, msg: dict, chat_id) -> None:
        self.stats["photos"] += 1
        mid = msg.get("message_id")
        self.client.send_message(chat_id, "✅ وێنەکە گەیشت! خەریکم بە پارچەپارچە بیخوێنمەوە و وەریگێڕم… ⏳", mid)
        fid = image_file_id(msg)
        if not fid:
            raise TelegramError("photo without file_id")
        src_hint = lang_hint(msg)
        raw = self.client.download(fid, self.outdir / f"in_{mid}.jpg")

        tm = self.factory.tm
        try:
            width, height = tm.image_size(raw)
        except Exception:
            width = height = 0

        # Do not stop at the first non-empty OCR result: on a Korean page,
        # English OCR can produce a few junk words. Keep the stronger result;
        # stop early only when the first language passes the quality gate.
        segs, used_src, best_score, qa, render_qa = [], src_hint, -1.0, None, None
        for src in dict.fromkeys([src_hint, "ko" if src_hint == "en" else "en"]):
            try:
                found = tm.ocr_image(raw, src)
            except Exception as e:
                print(f"OCR {src} failed: {e}", file=sys.stderr); found = []
            found = [s for s in found if len(s.text.strip()) >= 2][:240]
            if not found:
                continue
            found_qa = tm.assess_segments(width, height, found)
            found_render_qa = tm.assess_render_plan(width, height, found)
            fully_ok = bool(found_qa["ok"] and found_render_qa["ok"])
            score = tm.segments_score(tm.renderable_segments(found)) * (1.35 if fully_ok else 0.55)
            if score > best_score:
                segs, used_src, best_score, qa, render_qa = found, src, score, found_qa, found_render_qa
            if fully_ok:
                break

        if not segs:
            self.client.send_message(
                chat_id,
                "⚠️ لەم وێنەیەدا هیچ دەقێک نەخوێندرایەوە (OCR).\n"
                "تکایە بۆ وێنەی درێژ ڕەسەنەکە وەک File/Document بنێرە؛ وێنەی پەستێنراوی تلگرام دەقەکە زۆر بچووک دەکاتەوە.",
                mid)
            return

        self.stats["segments"] += len(segs)
        qa = qa or tm.assess_segments(width, height, segs)
        render_qa = render_qa or tm.assess_render_plan(width, height, segs)
        print(f"::notice::OCR QA src={used_src} {json.dumps(qa, ensure_ascii=False)}")
        print(f"::notice::RENDER QA {json.dumps(render_qa, ensure_ascii=False)}")
        # Geometry only (never page text) so a rejected live page can be tuned
        # from run annotations without uploading the user's image anywhere.
        for i, seg in enumerate(segs, 1):
            print(f"::notice::SEGMENT {i} chars={len(seg.text)} box={seg.box} "
                  f"conf={seg.confidence} bubble={seg.bubble_detected}/{seg.bubble_id} "
                  f"placed={seg.placement_confirmed} render_box={seg.render_box}")
        if not qa["ok"]:
            # The old bot confidently sent this kind of result. Now it must not:
            # a suspicious OCR pass is reported instead of 'translated' wrongly.
            self.stats["quality_rejected"] += 1
            debug = self.outdir / f"page_{mid}.debug.jpg"
            try:
                tm.render_debug_image(raw, segs, debug)
                self.client.send_document(
                    chat_id, debug,
                    caption="🔎 چوارچێوە سوورەکان ئەوە دەردەخەن چی خوێندرایەوە",
                    reply_to=mid)
            except Exception as e:
                print(f"::warning::debug image failed ({type(e).__name__}: {e})")
            self.client.send_message(
                chat_id,
                "⚠️ خوێندنەوەی ئەم وێنەیە دڵنیا نییە، بۆیە وەرگێڕانێکی هەڵە نانێرم.\n"
                f"OCR تەنها {len(segs)} بەشی دەقی دۆزیەوە. تکایە ڕەسەنەکە وەک File/Document بنێرە "
                "(لە کاتی ناردندا File هەڵبژێرە، نەک Photo)، یان بەشێکی ڕووناکتری لاپەڕەکە ببڕە.",
                mid)
            return

        translator = self.factory.get(used_src)
        for s, ck in zip(segs, translator.translate_many([s.text for s in segs])):
            s.ckb = ck

        pairs = "\n\n".join(f"[{i}]\n{s.text}\n→ {s.ckb}" for i, s in enumerate(segs, 1))
        render_segs = tm.renderable_segments(segs)
        if not render_qa["ok"] or not render_segs:
            # OCR text may be usable, but its coordinates are not trustworthy.
            # Never repeat the bad sample: no final image, only debug + text.
            self.stats["render_rejected"] += 1
            debug = self.outdir / f"page_{mid}.debug.jpg"
            try:
                tm.render_debug_image(raw, segs, debug)
                self.client.send_document(
                    chat_id, debug,
                    caption="🔎 سوور=دەقی OCR، سەوز=هەڵۆکی پشتڕاستکراوە، پرتەقاڵی=پاشبنەمای تەخت، ڕەساسی=نادڵنیا",
                    reply_to=mid)
            except Exception as e:
                print(f"::warning::debug image failed ({type(e).__name__}: {e})")
            self.client.send_message(
                chat_id,
                "⚠️ دەقەکان خوێندراونەتەوە، بەڵام شوێنی هەڵۆکەکان دڵنیا نین؛ "
                "بۆیە وێنەی کۆتاییە هەڵەکە نانێرم. دەقەکان لە خوارەوە دەنێرم. "
                "بۆ ئەنجامێکی باشتر ڕەسەنەکە وەک File/Document بنێرە.",
                mid)
            self.client.send_message(chat_id, f"📝 دەقەکان:\n\n{pairs}", mid)
            return

        rendered = self.outdir / f"page_{mid}.ckb.jpg"
        try:
            tm.render_image(raw, render_segs, rendered)
            self.stats["rendered_segments"] += len(render_segs)
            method = choose_send_method(width, height, rendered.stat().st_size)
            caption = (f"✅ وەرگێڕدرا بە کوردی سۆرانی — "
                       f"{len(render_segs)} هەڵۆک بە سەرکەوتوویی دانران")
            if method == "document":
                sent = self.client.send_document(chat_id, rendered, caption=caption, reply_to=mid)
                self.stats["document_replies"] += 1
            else:
                sent = self.client.send_photo(chat_id, rendered, caption=caption, reply_to=mid)
                self.stats["photo_replies"] += 1
            print(f"::notice::IMAGE TRANSLATED method={method} segments={len(segs)} "
                  f"rendered_segments={len(render_segs)} sent_message_id={sent.get('message_id')}")
        except Exception as e:  # rendering is bonus; text reply below still goes out
            print(f"::warning::render/send failed ({type(e).__name__}: {e}); sending text only")
            try:
                self.client.send_message(
                    chat_id,
                    "⚠️ نەمتوانی وێنە کۆتاییەکە بە دروستی دروست بکەم (فۆنت/ڕێندەر)، "
                    "بۆیە تەنها دەقەکان دەنێرم لە جیاتی وێنەیەکی خراپ.",
                    mid)
            except Exception:
                pass
        self.client.send_message(chat_id, f"📝 دەقەکان:\n\n{pairs}", mid)

    def run_once(self, state: State, state_path: Path) -> dict:
        me = self.client.api("getMe")
        print(f"::notice::BOT OK @{me.get('username')}")
        updates = sorted(self.client.get_updates(state.next_offset), key=lambda u: u["update_id"])
        self.stats["updates"] = len(updates)
        print(f"::notice::INBOX updates={len(updates)} next_offset={state.next_offset}")
        if not updates:
            state.save(state_path)
            return self.stats
        failed: list[int] = []
        for update in updates:
            uid = update["update_id"]
            if uid in state.processed:
                state.mark(uid); state.save(state_path); continue
            try:
                self.process(update)
                state.mark(uid)
            except Exception as e:
                self.stats["errors"] += 1
                print(f"::error::update {uid} failed: {type(e).__name__}: {e}")
                exhausted = state.fail(uid)
                if not exhausted:
                    failed.append(uid)  # keep it queued on Telegram for a later retry
                chat_id = ((update.get("message") or {}).get("chat") or {}).get("id")
                if chat_id and exhausted:
                    try:
                        self.client.send_message(chat_id, "⚠️ ببورە، هەرچۆنێک بێت نەمتوانی ئەوە کار پێبکەم. دووبارە بینێرە.")
                    except Exception:
                        pass
            state.save(state_path)
        if failed:
            # Do not acknowledge failed updates to Telegram: rewind the offset
            # to the oldest failure (successful ones stay deduped in processed[]).
            state.next_offset = min(state.next_offset, min(failed))
            state.save(state_path)
        return self.stats

# ---------------------------------------------------------------- CLI
def gh_output(**items) -> None:
    for k, v in items.items():
        print(f"{k}={v}")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            for k, v in items.items():
                f.write(f"{k}={v}\n")

def peek(client: TelegramClient, state: State) -> int:
    updates = client.get_updates(state.next_offset)
    fresh = [u for u in updates if u["update_id"] not in state.processed]
    needs_ocr = any(classify_message(u.get("message") or {}) == "photo" for u in fresh)
    print(f"::notice::PEEK fresh={len(fresh)} needs_ocr={str(needs_ocr).lower()}")
    gh_output(has_updates=str(bool(fresh)).lower(), needs_ocr=str(needs_ocr).lower(),
              fresh_count=len(fresh))
    return 0

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="One-pass Telegram manhwa bot inbox")
    ap.add_argument("--state", default=os.environ.get("BOT_STATE_FILE", str(ROOT / "bot" / "state" / "processed.json")))
    ap.add_argument("--outdir", default=os.environ.get("BOT_OUTDIR", str(ROOT / "bot" / "out")))
    ap.add_argument("--peek", action="store_true", help="only report queued updates (for CI gating)")
    a = ap.parse_args(argv)

    token, admin = os.environ.get("TELEGRAM_BOT_TOKEN", ""), os.environ.get("TELEGRAM_ADMIN_ID", "")
    if not token:
        print("::warning::TELEGRAM_BOT_TOKEN secret not set; nothing to do.")
        gh_output(has_updates="false", needs_ocr="false", fresh_count=0)
        return 0
    state_path = Path(a.state)
    state = State.load(state_path)
    client = TelegramClient(token)
    if a.peek:
        return peek(client, state)
    proc = InboxProcessor(client, admin, Path(a.outdir))
    stats = proc.run_once(state, state_path)
    print(f"::notice::INBOX DONE {json.dumps(stats)}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
