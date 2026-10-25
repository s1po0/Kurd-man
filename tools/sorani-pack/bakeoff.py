"""Bake-off for the Sorani offline pack: current Helsinki opus-mt-en-ine
(v1 pack, quantized, from the sorani-pack-v1 release) vs the dedicated
farukclk/opus-mt-en-ckb-nllb-full fine-tune (exported + quantized here),
both with beam search on manhwa-style sentences. Mirrors the Android
runner (full-sequence decoder graph, batched beams)."""
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


def notice(title, msg):
    msg = str(msg).replace("%", "%25").replace("\r", " ").replace("\n", " ⏎ ")
    print(f"::notice title={title}::{msg[:950]}", flush=True)


def beam_search(enc, dec, ids, start_id, eos_id, banned, max_len=96):
    ids_np = np.array([ids], dtype=np.int64)
    mask_np = np.ones_like(ids_np)
    hidden = enc.run(None, {"input_ids": ids_np,
                            "attention_mask": mask_np})[0]
    # beams: list of (cum_logprob, generated_ids, finished)
    beams = [(0.0, [], False)]
    for _ in range(max_len):
        live = [b for b in beams if not b[2]]
        if not live:
            break
        seqs = np.array([[start_id] + b[1] for b in live], dtype=np.int64)
        b_hidden = np.repeat(hidden, len(live), axis=0)
        b_mask = np.repeat(mask_np, len(live), axis=0)
        logits = dec.run(None, {
            "input_ids": seqs,
            "encoder_hidden_states": b_hidden,
            "encoder_attention_mask": b_mask,
        })[0][:, -1, :].astype(np.float64)
        for b in banned:
            if b < logits.shape[1]:
                logits[:, b] = -1e30
        # log-softmax
        m = logits.max(axis=1, keepdims=True)
        logp = logits - m - np.log(np.exp(logits - m).sum(axis=1, keepdims=True))
        cand = []
        for bi, (score, gen, _) in enumerate(live):
            top = np.argpartition(-logp[bi], BEAMS)[:BEAMS]
            for t in top:
                cand.append((score + float(logp[bi, t]), gen + [int(t)],
                             int(t) == eos_id))
        cand.sort(key=lambda c: c[0], reverse=True)
        beams = cand[:BEAMS]
    def rank(b):
        return b[0] / (max(1, len(b[1])) ** ALPHA)
    beams.sort(key=rank, reverse=True)
    best = beams[0][1]
    return [t for t in best if t != eos_id]


def load_vocab(path):
    vocab = json.loads(Path(path).read_text(encoding="utf-8"))
    inv = {i: p for p, i in vocab.items()}
    return vocab, inv


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
           f"vocab={len(vocab)} eos={eos_id} unk={unk_id} pad={pad_id} "
           f"tag={tag_id} banned={len(banned)}")

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
    return results


def main():
    # --- Model B: export + quantize the dedicated fine-tune ---
    fp32 = Path("b-fp32")
    out_b = Path("b-out")
    out_b.mkdir(exist_ok=True)
    proc = subprocess.run(
        ["optimum-cli", "export", "onnx",
         "--model", "farukclk/opus-mt-en-ckb-nllb-full",
         "--task", "translation", "--opset", "14", "--atol", "1e-3",
         str(fp32)],
        capture_output=True, text=True)
    print(proc.stdout[-4000:], flush=True)
    print(proc.stderr[-4000:], flush=True)
    if proc.returncode != 0:
        for line in (proc.stderr or "").strip().splitlines()[-8:]:
            print(f"::error title=BEXPORT::{line.replace('%', '%25')[:800]}",
                  flush=True)
        raise SystemExit("B export failed")
    from onnxruntime.quantization import QuantType, quantize_dynamic
    for src_name, dst_name in (
            ("encoder_model.onnx", "encoder_model_quantized.onnx"),
            ("decoder_model.onnx", "decoder_model_quantized.onnx")):
        quantize_dynamic(str(fp32 / src_name), str(out_b / dst_name),
                         op_types_to_quantize=["MatMul", "Gather"],
                         per_channel=True, weight_type=QuantType.QInt8)
        print("quantized", dst_name, flush=True)
    for name in ("source.spm", "vocab.json"):
        shutil.copyfile(fp32 / name, out_b / name)

    notice("SENTENCES", " | ".join(f"[{i+1}] {s}" for i, s in
                                   enumerate(SENTENCES)))
    run_model("A_HELSINKI", "a-pack", ">>kur_Arab<<")
    run_model("B_FARUKCLK", "b-out", None)
    print("bake-off done", flush=True)


if __name__ == "__main__":
    main()
