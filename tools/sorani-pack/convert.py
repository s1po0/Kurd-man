"""Convert Helsinki-NLP/opus-mt-en-ine (Apache-2.0) to a small quantized ONNX
"Sorani language pack" for Koorix/KurdMan offline translation.

Runs in GitHub Actions (see .github/workflows/sorani-pack.yml).
Outputs into ./pack-out/ plus ::notice annotations with exact
file sizes/SHA-256 + ONNX IO names for the Android model manager,
and an end-to-end greedy translation test on the quantized graphs
(mirroring exactly what the Android runner feeds the graphs).
"""
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

MODEL_ID = "Helsinki-NLP/opus-mt-en-ine"
MODEL_REV = "3b10842ae12d8d71031b8c362dabbd160fd7b6a5"
OUT = Path("pack-out")
FP32 = Path("fp32")
PAD_ID = 61160
EOS_ID = 0


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def notice(title: str, msg: str) -> None:
    msg = str(msg).replace("%", "%25").replace("\r", " ").replace("\n", " ")
    print(f"::notice title={title}::{msg[:900]}", flush=True)


def _generate(enc, dec, ids, banned, np):
    ids_np = np.array([ids], dtype=np.int64)
    mask_np = np.ones_like(ids_np)
    hidden = enc.run(None, {"input_ids": ids_np,
                            "attention_mask": mask_np})[0]
    generated = []
    for _ in range(96):
        logits = dec.run(None, {
            "input_ids": np.array([[PAD_ID] + generated], dtype=np.int64),
            "encoder_hidden_states": hidden,
            "encoder_attention_mask": mask_np,
        })[0][0, -1].copy()
        for b in banned:
            if b < logits.shape[0]:
                logits[b] = -1e9
        nxt = int(np.argmax(logits))
        if nxt == EOS_ID:
            break
        generated.append(nxt)
    return generated


def _degenerate(ids):
    if len(ids) < 4:
        return True
    return len(set(ids)) < max(3, len(ids) // 3)


def greedy_test(enc_path: Path, dec_path: Path) -> None:
    """Isolate failures: fp32 vs quantized, HF tokenizer vs the app's
    spm+vocab.json parity path. Full-sequence greedy (no KV inputs)."""
    import numpy as np
    import onnxruntime as ort
    import sentencepiece as spm
    from transformers import AutoTokenizer

    src = spm.SentencePieceProcessor(model_file=str(OUT / "source.spm"))
    vocab = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    inv = {i: piece for piece, i in vocab.items()}
    tag = vocab[">>kur_Arab<<"]
    banned = {i for piece, i in vocab.items()
              if piece.startswith(">>") and piece.endswith("<<")}
    banned.add(PAD_ID)
    hf = AutoTokenizer.from_pretrained(MODEL_ID)
    notice("TESTTAG", f"kur_tag_id={tag} vocab={len(vocab)} banned={len(banned)}")

    def enc_hf(text):
        return hf(f">>kur_Arab<< {text}")["input_ids"]

    def enc_par(text):
        pieces = src.encode(text, out_type=str)
        return [tag] + [vocab.get(piece, 1) for piece in pieces] + [EOS_ID]

    def dec_hf(ids):
        return hf.decode(ids, skip_special_tokens=True)

    def dec_par(ids):
        out, pending = [], bytearray()
        for i in ids:
            piece = inv.get(i)
            if piece is None or i in banned or i == EOS_ID:
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

    text = "Where are you going? I will wait for you at the station."
    notice("TESTENC", f"hf={enc_hf(text) == enc_par(text)} "
                      f"hf_ids={enc_hf(text)} par_ids={enc_par(text)}")

    def run_case(label, enc_p, dec_p, enc_fn, dec_fn):
        enc = ort.InferenceSession(str(enc_p),
                                   providers=["CPUExecutionProvider"])
        dec = ort.InferenceSession(str(dec_p),
                                   providers=["CPUExecutionProvider"])
        gen = _generate(enc, dec, enc_fn(text), banned, np)
        out = dec_fn(gen)
        notice(label, f"gen={len(gen)} degen={_degenerate(gen)} :: {out}")
        return not _degenerate(gen) and bool(out.strip())

    ok_fp32_hf = run_case("CASE_FP32_HF", FP32 / "encoder_model.onnx",
                          FP32 / "decoder_model.onnx", enc_hf, dec_hf)
    ok_fp32_par = run_case("CASE_FP32_PAR", FP32 / "encoder_model.onnx",
                           FP32 / "decoder_model.onnx", enc_par, dec_par)
    ok_q = run_case("CASE_Q_PAR", enc_path, dec_path, enc_par, dec_par)
    notice("CASES", f"fp32_hf={ok_fp32_hf} fp32_par={ok_fp32_par} q_par={ok_q}")
    if not ok_q:
        raise SystemExit("quantized app-parity case failed")


def main() -> None:
    OUT.mkdir(exist_ok=True)

    print("== Exporting ONNX (fp32) ==", flush=True)
    proc = subprocess.run(
        [
            "optimum-cli", "export", "onnx",
            "--model", MODEL_ID,
            "--task", "translation",
            "--opset", "14",
            "--atol", "1e-3",
            str(FP32),
        ],
        capture_output=True, text=True,
    )
    print(proc.stdout[-6000:], flush=True)
    print(proc.stderr[-6000:], flush=True)
    if proc.returncode != 0:
        tail = (proc.stderr or proc.stdout).strip().splitlines()[-25:]
        for line in tail:
            clean = line.replace("%", "%25").replace("\r", " ")
            print(f"::error title=EXPORTFAIL::{clean[:800]}", flush=True)
        raise SystemExit(f"optimum-cli failed with code {proc.returncode}")
    for p in sorted(FP32.rglob("*")):
        if p.is_file():
            print("  fp32:", p, p.stat().st_size, flush=True)

    print("== Quantizing (dynamic int8) ==", flush=True)
    from onnxruntime.quantization import QuantType, quantize_dynamic

    jobs = [
        ("encoder_model.onnx", "encoder_model_quantized.onnx"),
        ("decoder_model.onnx", "decoder_model_quantized.onnx"),
    ]
    if (FP32 / "decoder_with_past_model.onnx").exists():
        jobs.append(("decoder_with_past_model.onnx",
                     "decoder_with_past_model_quantized.onnx"))
        notice("WPAST", "decoder_with_past_model.onnx exported")
    else:
        notice("WPAST", "NO decoder_with_past_model.onnx")
    for src_name, dst_name in jobs:
        src = FP32 / src_name
        if not src.exists():
            raise SystemExit(f"missing exported file: {src}")
        dst = OUT / dst_name
        try:
            quantize_dynamic(
                str(src), str(dst),
                op_types_to_quantize=["MatMul", "Gather"],
                per_channel=True,
                weight_type=QuantType.QInt8,
            )
            print(f"  quantized (MatMul+Gather): {dst_name}", flush=True)
        except Exception as e:  # fall back to MatMul-only
            print(f"  Gather quant failed ({e}); MatMul-only", flush=True)
            quantize_dynamic(
                str(src), str(dst),
                op_types_to_quantize=["MatMul"],
                per_channel=True,
                weight_type=QuantType.QInt8,
            )

    # Tokenizer files (copied verbatim from the Helsinki repo export).
    for name in ("source.spm", "target.spm", "vocab.json"):
        src = FP32 / name
        if not src.exists():
            from huggingface_hub import hf_hub_download
            src = Path(hf_hub_download(
                MODEL_ID, name, revision=MODEL_REV))
        shutil.copyfile(src, OUT / name)
        print("  tokenizer:", name, flush=True)

    print("== Verifying quantized graphs (greedy end-to-end) ==", flush=True)
    greedy_test(OUT / "encoder_model_quantized.onnx",
                OUT / "decoder_model_quantized.onnx")

    specs = []
    for p in sorted(OUT.iterdir()):
        if not p.is_file():
            continue
        size = p.stat().st_size
        digest = sha256(p)
        specs.append({"file": p.name, "size": size, "sha256": digest})
        notice("PACKSPEC", f"{p.name} size={size} sha256={digest}")

    (OUT / "pack.json").write_text(json.dumps(
        {"model": MODEL_ID, "revision": MODEL_REV, "files": specs},
        indent=2))
    total = sum(s["size"] for s in specs)
    notice("PACKTOTAL", f"total_bytes={total}")
    print(f"Done. Total pack size: {total / 1e6:.1f} MB", flush=True)


if __name__ == "__main__":
    main()
