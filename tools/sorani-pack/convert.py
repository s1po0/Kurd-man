"""Convert Helsinki-NLP/opus-mt-en-ine (Apache-2.0) to the Sorani
offline language pack (v2).

v2 uses QUInt8 dynamic quantization (the Xenova/mobile-standard
unsigned-weight format): v1's signed-QInt8 graphs computed correct
results on some CPU kernels and garbage on others, while QUInt8
matches the fp32 top-token distribution. The pack is
verified end-to-end with the exact app algorithm (SentencePiece +
vocab.json, batched beam-4 + no-repeat-ngram + length cap) on five
manhwa-style sentences before publishing.
"""
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

import numpy as np

MODEL_ID = "Helsinki-NLP/opus-mt-en-ine"
MODEL_REV = "3b10842ae12d8d71031b8c362dabbd160fd7b6a5"
OUT = Path("pack-out")
FP32 = Path("fp32")
PAD_ID = 61160
EOS_ID = 0
BEAMS = 4
ALPHA = 0.6
NGRAM = 3

SENTENCES = [
    "Where are you going? I will wait for you at the station.",
    "I can't believe it... he is still alive!",
    "Don't touch my sister, or I will kill you.",
    "You are the only one who can save this kingdom now.",
    "Haha! Did you really think you could defeat me with that toy sword?",
]

CALIBRATION_TEXTS = [
    "What are you doing here?", "I did not expect to see you again.",
    "Run! They are coming after us.", "Please, give me one more chance.",
    "The king has ordered your arrest.", "She smiled and walked away.",
    "This sword was given to me by my father.", "We must leave before dawn.",
    "Are you really the strongest hunter?", "Stop hiding and fight me!",
    "I will protect you with my life.", "That monster killed my brother.",
    "Where is the entrance to the dungeon?", "His eyes turned red with rage.",
    "Do not underestimate me again.", "The village was burned to the ground.",
    "Tell me the truth about that night.", "You have grown much stronger.",
    "Let go of my hand right now!", "Everyone in the guild trusts you.",
    "A strange light appeared in the sky.", "He pulled out a hidden dagger.",
    "If you die here, everything ends.", "The princess is waiting for us.",
    "Why did you betray your own master?", "This potion will heal your wounds.",
    "Be careful, the floor is full of traps.", "I heard a voice inside the cave.",
    "Tomorrow we attack the castle at sunrise.", "She refused to answer his question.",
    "Your magic is useless against me.", "The old man laughed loudly.",
    "There is no time to explain, just run.", "He was born with a cursed mark.",
    "Do you remember our promise?", "The enemy army is approaching the gate.",
    "Take this letter to the capital.", "I never wanted to become a hero.",
    "Her tears fell on the cold stone.", "Stand up and face me, coward!",
    "The treasure is buried under the temple.", "You look tired, rest for a while.",
    "Something heavy crashed behind the door.", "He whispered a spell under his breath.",
    "That was only the beginning of the war.", "Please teach me how to use a sword.",
    "The rain finally stopped at midnight.", "Nobody has returned from that forest alive.",
    "With this ring, I can open any door.", "His wound is deeper than it looks.",
    "We found the missing children near the river.", "Shut up and listen to me carefully.",
    "The dragon opened its huge wings.", "You are not my real father, are you?",
    "After ten years, he came back home.", "This is the last warning I will give you.",
    "She drew her bow and aimed at the beast.", "Hold the line until reinforcements arrive!",
    "I swear on my honor that I am innocent.", "The ground shook beneath their feet.",
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def notice(title: str, msg: str) -> None:
    msg = str(msg).replace("%", "%25").replace("\r", " ").replace("\n", " ⏎ ")
    print(f"::notice title={title}::{msg[:950]}", flush=True)


def _banned_by_ngram(gen, n=NGRAM):
    if len(gen) < n - 1:
        return set()
    prefix = tuple(gen[-(n - 1):])
    out = set()
    for i in range(len(gen) - n + 1):
        if tuple(gen[i:i + n - 1]) == prefix:
            out.add(gen[i + n - 1])
    return out


def beam_search(enc, dec, ids, start_id, eos_id, banned):
    ids_np = np.array([ids], dtype=np.int64)
    mask_np = np.ones_like(ids_np)
    hidden = enc.run(None, {"input_ids": ids_np,
                            "attention_mask": mask_np})[0]
    max_len = min(96, max(24, int(len(ids) * 1.6) + 16))
    beams = [(0.0, [], False)]
    for _ in range(max_len):
        live = [b for b in beams if not b[2]]
        if not live:
            break
        seqs = np.array([[start_id] + b[1] for b in live], dtype=np.int64)
        logits = dec.run(None, {
            "input_ids": seqs,
            "encoder_hidden_states": np.repeat(hidden, len(live), axis=0),
            "encoder_attention_mask": np.repeat(mask_np, len(live), axis=0),
        })[0][:, -1, :].astype(np.float64)
        for b in banned:
            if b < logits.shape[1]:
                logits[:, b] = -1e30
        m = logits.max(axis=1, keepdims=True)
        logp = logits - m - np.log(np.exp(logits - m).sum(axis=1, keepdims=True))
        cand = []
        for bi, (score, gen, _) in enumerate(live):
            blocked = _banned_by_ngram(gen)
            top = np.argpartition(-logp[bi], BEAMS * 3)[:BEAMS * 3]
            added = 0
            for t in top[np.argsort(-logp[bi][top])]:
                t = int(t)
                if t in blocked and added >= 1:
                    continue
                cand.append((score + float(logp[bi, t]), gen + [t],
                             t == eos_id))
                added += 1
                if added >= BEAMS:
                    break
        cand.sort(key=lambda c: c[0], reverse=True)
        beams = cand[:BEAMS]
    def rank(b):
        return b[0] / (max(1, len(b[1])) ** ALPHA)
    beams.sort(key=rank, reverse=True)
    return [t for t in beams[0][1] if t != eos_id]


def piece_decode(ids, inv, banned, eos_id):
    out, pending = [], bytearray()
    for i in ids:
        piece = inv.get(i)
        if piece is None or i in banned or i == eos_id:
            continue
        if len(piece) == 6 and piece.startswith("<0x") and piece.endswith(">"):
            pending.append(int(piece[3:5], 16))
            continue
        if pending:
            out.append(pending.decode("utf-8", "replace"))
            pending = bytearray()
        out.append(piece)
    if pending:
        out.append(pending.decode("utf-8", "replace"))
    return "".join(out).replace("\u2581", " ").strip()


def _degenerate(ids):
    if len(ids) < 4:
        return True
    return len(set(ids)) < max(3, len(ids) // 3)


def verify_pack():
    """App-parity end-to-end test on the quantized pack files."""
    import onnxruntime as ort
    import sentencepiece as spm
    src = spm.SentencePieceProcessor(model_file=str(OUT / "source.spm"))
    vocab = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    inv = {i: p for p, i in vocab.items()}
    tag = vocab[">>kur_Arab<<"]
    banned = {i for p, i in vocab.items()
              if p.startswith(">>") and p.endswith("<<")}
    banned.add(PAD_ID)

    def encode(text):
        pieces = src.encode(text, out_type=str)
        return [tag] + [vocab.get(p, 1) for p in pieces] + [EOS_ID]

    enc = ort.InferenceSession(str(OUT / "encoder_model_quantized.onnx"),
                               providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(str(OUT / "decoder_model_quantized.onnx"),
                               providers=["CPUExecutionProvider"])
    for j, text in enumerate(SENTENCES, 1):
        gen = beam_search(enc, dec, encode(text), PAD_ID, EOS_ID, banned)
        out = piece_decode(gen, inv, banned, EOS_ID)
        notice(f"TESTOUT{j}", f"EN: {text} => CKB: {out}")
        arab = sum(1 for ch in out if "\u0600" <= ch <= "\u06ff")
        if _degenerate(gen) or arab < 3:
            raise SystemExit(f"verification failed on sentence {j}: {out!r}")


def main() -> None:
    OUT.mkdir(exist_ok=True)

    print("== Exporting ONNX (fp32) ==", flush=True)
    proc = subprocess.run(
        ["optimum-cli", "export", "onnx", "--model", MODEL_ID,
         "--task", "translation", "--opset", "14", "--atol", "1e-3",
         str(FP32)],
        capture_output=True, text=True)
    print(proc.stdout[-4000:], flush=True)
    print(proc.stderr[-4000:], flush=True)
    if proc.returncode != 0:
        for line in (proc.stderr or "").strip().splitlines()[-10:]:
            print(f"::error title=EXPORTFAIL::"
                  f"{line.replace('%', '%25')[:800]}", flush=True)
        raise SystemExit(f"optimum-cli failed with code {proc.returncode}")

    for name in ("source.spm", "vocab.json"):
        shutil.copyfile(FP32 / name, OUT / name)

    print("== Quantizing (dynamic QUInt8) ==", flush=True)
    # QUInt8 (unsigned weights) is the battle-tested mobile path
    # (Xenova/transformers.js). The signed QInt8 graphs of v1 computed
    # garbage on some CPU kernels; QUInt8 matches fp32 top tokens.
    from onnxruntime.quantization import QuantType, quantize_dynamic
    for src_name, dst_name in (
            ("encoder_model.onnx", "encoder_model_quantized.onnx"),
            ("decoder_model.onnx", "decoder_model_quantized.onnx")):
        quantize_dynamic(
            str(FP32 / src_name), str(OUT / dst_name),
            op_types_to_quantize=["MatMul", "Gather"],
            per_channel=True,
            weight_type=QuantType.QUInt8,
        )
        print("quantized (QUInt8 dynamic):", dst_name, flush=True)

    print("== Verifying quantized pack (app-parity beam) ==", flush=True)
    verify_pack()

    specs = []
    for p in sorted(OUT.iterdir()):
        if p.is_file():
            specs.append({"file": p.name, "size": p.stat().st_size,
                          "sha256": sha256(p)})
            notice("PACKSPEC", f"{p.name} size={p.stat().st_size} "
                               f"sha256={specs[-1]['sha256']}")
    (OUT / "pack.json").write_text(json.dumps(
        {"model": MODEL_ID, "revision": MODEL_REV, "quant": "quint8-dynamic",
         "files": specs}, indent=2))
    total = sum(s["size"] for s in specs)
    notice("PACKTOTAL", f"total_bytes={total}")
    print(f"Done. Total pack size: {total / 1e6:.1f} MB", flush=True)


if __name__ == "__main__":
    main()
