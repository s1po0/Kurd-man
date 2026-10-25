"""Bake-off round 2 for the Sorani offline pack (no NLLB, pack-style
small models only): Helsinki opus-mt-en-ine (v1 release, quantized) vs
opus-mt-ine-ine (exported + quantized here), both with the improved
decoding planned for the app: batched beam-4 + no-repeat-ngram(3) +
source-relative length cap + length-penalty ranking."""
import json
import shutil
import subprocess
from pathlib import Path

import numpy as np

SENTENCES = [
    "Where are you going? I will wait for you at the station.",
    "I can't believe it... he is still alive!",
    "Don't touch my sister, or I will kill you.",
    "You are the only one who can save this kingdom now.",
    "Haha! Did you really think you could defeat me with that toy sword?",
]
BEAMS = 4
ALPHA = 0.6
NGRAM = 3


def notice(title, msg):
    msg = str(msg).replace("%", "%25").replace("\r", " ").replace("\n", " ⏎ ")
    print(f"::notice title={title}::{msg[:950]}", flush=True)


def _banned_by_ngram(gen, n=NGRAM):
    if len(gen) < n - 1:
        return set()
    prefix = tuple(gen[-(n - 1):]) if n > 1 else ()
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
                    continue  # keep at least one fallback candidate
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


def load_vocab(path):
    vocab = json.loads(Path(path).read_text(encoding="utf-8"))
    return vocab, {i: p for p, i in vocab.items()}


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


def run_model(label, pack_dir, tag_piece):
    import onnxruntime as ort
    import sentencepiece as spm
    pack = Path(pack_dir)
    src = spm.SentencePieceProcessor(model_file=str(pack / "source.spm"))
    vocab, inv = load_vocab(pack / "vocab.json")
    eos_id = vocab["</s>"]
    unk_id = vocab.get("<unk>", 1)
    pad_id = vocab["<pad>"]
    tag_id = vocab.get(tag_piece) if tag_piece else None
    banned = {i for p, i in vocab.items()
              if p.startswith(">>") and p.endswith("<<")}
    banned.add(pad_id)
    notice(f"{label}_META",
           f"vocab={len(vocab)} eos={eos_id} pad={pad_id} tag={tag_id}")

    def encode(text):
        pieces = src.encode(text, out_type=str)
        ids = [vocab.get(p, unk_id) for p in pieces]
        if tag_id is not None:
            ids = [tag_id] + ids
        return ids + [eos_id]

    enc = ort.InferenceSession(str(pack / "encoder_model_quantized.onnx"),
                               providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(str(pack / "decoder_model_quantized.onnx"),
                               providers=["CPUExecutionProvider"])
    results = []
    for text in SENTENCES:
        gen = beam_search(enc, dec, encode(text), pad_id, eos_id, banned)
        results.append(piece_decode(gen, inv, banned, eos_id))
    for j in range(0, len(results), 3):
        chunk = " | ".join(
            f"[{j + k + 1}] {r}" for k, r in enumerate(results[j:j + 3]))
        notice(f"{label}_OUT{j // 3 + 1}", chunk)


def export_quantize(model_id, fp32_dir, out_dir):
    fp32, out = Path(fp32_dir), Path(out_dir)
    out.mkdir(exist_ok=True)
    proc = subprocess.run(
        ["optimum-cli", "export", "onnx", "--model", model_id,
         "--task", "translation", "--opset", "14", "--atol", "1e-3",
         str(fp32)],
        capture_output=True, text=True)
    print(proc.stdout[-4000:], flush=True)
    print(proc.stderr[-4000:], flush=True)
    if proc.returncode != 0:
        for line in (proc.stderr or "").strip().splitlines()[-8:]:
            print(f"::error title=CEXPORT::{line.replace('%', '%25')[:800]}",
                  flush=True)
        raise SystemExit("C export failed")
    from onnxruntime.quantization import QuantType, quantize_dynamic
    for src_name, dst_name in (
            ("encoder_model.onnx", "encoder_model_quantized.onnx"),
            ("decoder_model.onnx", "decoder_model_quantized.onnx")):
        quantize_dynamic(str(fp32 / src_name), str(out / dst_name),
                         op_types_to_quantize=["MatMul", "Gather"],
                         per_channel=True, weight_type=QuantType.QInt8)
        print("quantized", dst_name, flush=True)
    for name in ("source.spm", "vocab.json"):
        shutil.copyfile(fp32 / name, out / name)


def main():
    export_quantize("Helsinki-NLP/opus-mt-ine-ine", "c-fp32", "c-out")
    notice("SENTENCES", " | ".join(
        f"[{i+1}] {s}" for i, s in enumerate(SENTENCES)))
    run_model("A_ENINE", "a-pack", ">>kur_Arab<<")
    run_model("C_INEINE", "c-out", ">>kur_Arab<<")
    print("bake-off 2 done", flush=True)


if __name__ == "__main__":
    main()
