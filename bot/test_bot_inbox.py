"""Offline tests for bot_inbox — no network, no OCR. Run from bot/:
    python -m unittest test_bot_inbox -v
"""
import tempfile, unittest
from pathlib import Path
from bot_inbox import (InboxProcessor, State, chunk_message, classify_message,
                       image_file_id, lang_hint, pick_largest_photo)

PHOTO_MSG = {"message_id": 7, "chat": {"id": 42}, "from": {"id": 999},
             "photo": [{"file_id": "small", "width": 90, "height": 90, "file_size": 1000},
                       {"file_id": "big", "width": 800, "height": 1200, "file_size": 90000}]}

class FakeClient:
    def __init__(self, updates=None):
        self.updates, self.sent = updates or [], []
    def api(self, method, payload=None, timeout=30):
        assert method == "getMe"; return {"username": "testbot"}
    def get_updates(self, offset=0):
        return [u for u in self.updates if u["update_id"] >= offset] if offset else list(self.updates)
    def send_message(self, chat, text, reply=None, *a):
        self.sent.append((chat, text)); return [{"message_id": len(self.sent)}]

class TestHelpers(unittest.TestCase):
    def test_chunk(self):
        parts = chunk_message("یەک\n" * 3000)
        self.assertTrue(all(len(p) <= 3900 for p in parts)); self.assertGreater(len(parts), 2)
        self.assertEqual(chunk_message(""), [])

    def test_largest_photo_and_classify(self):
        self.assertEqual(pick_largest_photo(PHOTO_MSG["photo"])["file_id"], "big")
        self.assertEqual(image_file_id(PHOTO_MSG), "big")
        self.assertEqual(classify_message(PHOTO_MSG), "photo")
        self.assertEqual(classify_message({"text": "/start"}), "command")
        self.assertEqual(classify_message({"text": "Hello"}), "text")
        self.assertEqual(classify_message({"document": {"mime_type": "image/png", "file_id": "d"}}), "photo")
        self.assertEqual(classify_message({"sticker": {}}), "other")

    def test_lang_hint(self):
        self.assertEqual(lang_hint({"caption": "ko"}), "ko")
        self.assertEqual(lang_hint({"photo": []}), "en")

    def test_state_dedupes_across_runs(self):
        upd = [{"update_id": 10, "message": {"message_id": 1, "chat": {"id": 42},
                                             "from": {"id": 999}, "text": "/ping"}}]
        with tempfile.TemporaryDirectory() as d:
            path, client = Path(d) / "s.json", FakeClient(upd)
            InboxProcessor(client, "999", Path(d)).run_once(State.load(path), path)
            self.assertEqual(State.load(path).next_offset, 11)
            # Telegram resends nothing >= offset; even if it did, processed[] protects us
            InboxProcessor(client, "999", Path(d)).run_once(State.load(path), path)
            pongs = [t for _, t in client.sent if "pong" in t]
            self.assertEqual(len(pongs), 1)

    def test_admin_guard(self):
        upd = [{"update_id": 5, "message": {"message_id": 1, "chat": {"id": 7},
                                            "from": {"id": 123}, "text": "/ping"}}]
        with tempfile.TemporaryDirectory() as d:
            client = FakeClient(upd)
            InboxProcessor(client, "999", Path(d)).run_once(State.load(Path(d) / "s"), Path(d) / "s")
            self.assertTrue(any("تایبەت" in t for _, t in client.sent))
            self.assertFalse(any("pong" in t for _, t in client.sent))

if __name__ == "__main__":
    unittest.main()
