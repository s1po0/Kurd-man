"""Diagnostic matrix: fp32 vs dynamic-int8 vs QDQ-int8 graphs of
opus-mt-en-ine, greedy (batch=1) vs beam-4 (batched), same runner.
Prints step-1 top tokens per graph to localize numeric divergence."""
import json
import subprocess
from pathlib import Path

import numpy as np

PAD_ID = 61160
EOS_ID = 0
TEXT = "Where are you going? I will wait for you at the station."
TEXT3 = "Don't touch my sister, or I will kill you."


def notice(title, msg):
    msg = str(msg).replace("%", "%25").replace("\r", " ").replace("\n", " ⏎ ")
    print(f"::notice title={title}::{msg[:950]}", flush=True)


def main():
    import onnxruntime as ort
    import sentencepiece as spm

    # Export fp32 fresh.
    proc = subprocess.run(
        ["optimum-cli", "export", "onnx", "--model",
         "Helsinki-NLP/opus-mt-en-ine", "--task", "translation",
         "--opset", "14", "--atol", "1e-3", "fp32"],
        capture_output=True, text=True)
    if proc.returncode != 0:
        print(proc.stderr[-4000:], flush=True)
        raise SystemExit("export failed")

    pack = Path("a-pack")
    src = spm.SentencePieceProcessor(model_file=str(pack / "source.spm"))
    vocab = json.loads((pack / "vocab.json").read_text(encoding="utf-8"))
    inv = {i: p for p, i in vocab.items()}
    tag = vocab[">>kur_Arab<<"]
    banned = {i for p, i in vocab.items()
              if p.startswith(">>") and p.endswith("<<")}
    banned.add(PAD_ID)

    def encode(text):
        pieces = src.encode(text, out_type=str)
        return [tag] + [vocab.get(p, 1) for p in pieces] + [EOS_ID]

    def decode(ids):
        out = []
        for i in ids:
            piece = inv.get(i)
            if piece is None or i in banned or i == EOS_ID:
                continue
            out.append(piece)
        return "".join(out).replace("\u2581", " ").strip()

    def greedy(enc_p, dec_p, text):
        enc = ort.InferenceSession(str(enc_p),
                                   providers=["CPUExecutionProvider"])
        dec = ort.InferenceSession(str(dec_p),
                                   providers=["CPUExecutionProvider"])
        ids = np.array([encode(text)], dtype=np.int64)
        mask = np.ones_like(ids)
        hidden = enc.run(None, {"input_ids": ids,
                                "attention_mask": mask})[0]
        gen, top1 = [], None
        for step in range(48):
            logits = dec.run(None, {
                "input_ids": np.array([[PAD_ID] + gen], dtype=np.int64),
                "encoder_hidden_states": hidden,
                "encoder_attention_mask": mask,
            })[0][0, -1].copy()
            if step == 0:
                idx = np.argsort(-logits)[:5]
                top1 = ",".join(f"{inv.get(int(i))!r}:{logits[i]:.2f}"
                                for i in idx)
            for b in banned:
                logits[b] = -1e30
            nxt = int(np.argmax(logits))
            if nxt == EOS_ID:
                break
            gen.append(nxt)
        return top1, decode(gen)

    def beam(enc_p, dec_p, text, beams_n=4):
        enc = ort.InferenceSession(str(enc_p),
                                   providers=["CPUExecutionProvider"])
        dec = ort.InferenceSession(str(dec_p),
                                   providers=["CPUExecutionProvider"])
        ids = np.array([encode(text)], dtype=np.int64)
        mask = np.ones_like(ids)
        hidden = enc.run(None, {"input_ids": ids,
                                "attention_mask": mask})[0]
        beams = [(0.0, [], False)]
        for _ in range(48):
            live = [b for b in beams if not b[2]]
            if not live:
                break
            seqs = np.array([[PAD_ID] + b[1] for b in live],
                            dtype=np.int64)
            logits = dec.run(None, {
                "input_ids": seqs,
                "encoder_hidden_states": np.repeat(hidden, len(live), axis=0),
                "encoder_attention_mask": np.repeat(mask, len(live), axis=0),
            })[0][:, -1, :].astype(np.float64)
            for b in banned:
                logits[:, b] = -1e30
            m = logits.max(axis=1, keepdims=True)
            logp = logits - m - np.log(
                np.exp(logits - m).sum(axis=1, keepdims=True))
            cand = []
            for bi, (score, gen, _) in enumerate(live):
                top = np.argpartition(-logp[bi], beams_n)[:beams_n]
                for t in top:
                    cand.append((score + float(logp[bi, t]),
                                 gen + [int(t)], int(t) == EOS_ID))
            cand.sort(key=lambda c: c[0], reverse=True)
            beams = cand[:beams_n]
        beams.sort(key=lambda b: b[0] / (max(1, len(b[1])) ** 0.6),
                   reverse=True)
        return decode([t for t in beams[0][1] if t != EOS_ID])

    graphs = [
        ("FP32", Path("fp32/encoder_model.onnx"),
         Path("fp32/decoder_model.onnx")),
        ("DYNQ", pack / "encoder_model_quantized.onnx",
         pack / "decoder_model_quantized.onnx"),
    ]
    for label, enc_p, dec_p in graphs:
        top1, g = greedy(enc_p, dec_p, TEXT)
        notice(f"{label}_TOP1", top1)
        notice(f"{label}_GREEDY", g)
        notice(f"{label}_BEAM", beam(enc_p, dec_p, TEXT))
        _, g3 = greedy(enc_p, dec_p, TEXT3)
        notice(f"{label}_GREEDY3", g3)
    print("diagnostic done", flush=True)


if __name__ == "__main__":
    main()
