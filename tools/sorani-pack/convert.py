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


def greedy_test(enc_path: Path, dec_path: Path) -> None:
    """Run the exact greedy loop the Android app runs, in Python."""
    import numpy as np
    import onnxruntime as ort
    import sentencepiece as spm

    src = spm.SentencePieceProcessor(model_file=str(OUT / "source.spm"))
    tgt = spm.SentencePieceProcessor(model_file=str(OUT / "target.spm"))
    tag = src.piece_to_id(">>kur_Arab<<")
    notice("TESTTAG", f">>kur_Arab<< id={tag}")

    enc = ort.InferenceSession(str(enc_path),
                               providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(str(dec_path),
                               providers=["CPUExecutionProvider"])
    for sess, label in ((enc, "encoder"), (dec, "decoder")):
        for i in sess.get_inputs():
            notice("PACKIN", f"{label} {i.name} {i.type} {i.shape}")
        for o in sess.get_outputs():
            notice("PACKOUT", f"{label} {o.name} {o.type} {o.shape}")
    dec_inputs = {i.name for i in dec.get_inputs()}

    text = "Where are you going? I will wait for you at the station."
    ids = [tag] + src.encode(text) + [EOS_ID]
    ids_np = np.array([ids], dtype=np.int64)
    mask_np = np.ones_like(ids_np)
    hidden = enc.run(None, {"input_ids": ids_np,
                            "attention_mask": mask_np})[0]

    def outputs_by_name(sess, feed):
        vals = sess.run(None, feed)
        return {o.name: v for o, v in zip(sess.get_outputs(), vals)}

    feed = {"input_ids": np.array([[PAD_ID]], dtype=np.int64),
            "use_cache_branch": np.array(False)}
    if "encoder_hidden_states" in dec_inputs:
        feed["encoder_hidden_states"] = hidden
    if "encoder_attention_mask" in dec_inputs:
        feed["encoder_attention_mask"] = mask_np
    if "attention_mask" in dec_inputs:
        feed["attention_mask"] = mask_np
    out = outputs_by_name(dec, feed)
    past = {k.replace("present", "past_key_values", 1): v
            for k, v in out.items() if k.startswith("present")}
    notice("TESTPAST", f"past tensors={len(past)}")

    generated = []
    tok = int(np.argmax(out["logits"][0, -1]))
    for _ in range(80):
        if tok == EOS_ID:
            break
        generated.append(tok)
        feed = {"input_ids": np.array([[tok]], dtype=np.int64),
                "use_cache_branch": np.array(True)}
        feed.update(past)
        if "encoder_hidden_states" in dec_inputs:
            feed["encoder_hidden_states"] = hidden
        if "encoder_attention_mask" in dec_inputs:
            feed["encoder_attention_mask"] = mask_np
        if "attention_mask" in dec_inputs:
            feed["attention_mask"] = mask_np
        out = outputs_by_name(dec, feed)
        past = {k.replace("present", "past_key_values", 1): v
                for k, v in out.items() if k.startswith("present")}
        tok = int(np.argmax(out["logits"][0, -1]))

    decoded = tgt.decode(generated)
    notice("TESTSRC", text)
    notice("TESTOUT", decoded)
    if not decoded.strip():
        raise SystemExit("greedy test produced empty output")
    arab = sum(1 for ch in decoded if "\u0600" <= ch <= "\u06ff")
    notice("TESTARABIC", f"arabic_script_chars={arab}")
    if arab < 3:
        raise SystemExit("greedy test output is not Arabic-script Kurdish")


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
            notice("FP32FILE", f"{p.name} {p.stat().st_size}")

    print("== Quantizing (dynamic int8) ==", flush=True)
    from onnxruntime.quantization import QuantType, quantize_dynamic

    jobs = [
        ("encoder_model.onnx", "encoder_model_quantized.onnx"),
        ("decoder_model.onnx", "decoder_model_quantized.onnx"),
    ]
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
    for name in ("source.spm", "target.spm"):
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
