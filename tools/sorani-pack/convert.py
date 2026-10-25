"""Convert Helsinki-NLP/opus-mt-en-ine to a small quantized ONNX
"Sorani language pack" for Koorix/KurdMan offline translation.

Runs in GitHub Actions (see .github/workflows/sorani-pack.yml).
Outputs into ./pack-out/ plus ::notice annotations with exact
file sizes/SHA-256 + ONNX IO names for the Android model manager.
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


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def notice(title: str, msg: str) -> None:
    print(f"::notice title={title}::{msg}", flush=True)


def main() -> None:
    OUT.mkdir(exist_ok=True)

    print("== Exporting ONNX (fp32) ==", flush=True)
    subprocess.run(
        [
            "optimum-cli", "export", "onnx",
            "--model", MODEL_ID,
            "--task", "translation",
            "--opset", "14",
            str(FP32),
        ],
        check=True,
    )
    for p in sorted(FP32.rglob("*")):
        if p.is_file():
            print("  fp32:", p, p.stat().st_size, flush=True)

    print("== Quantizing (dynamic int8) ==", flush=True)
    from onnxruntime.quantization import QuantType, quantize_dynamic

    jobs = [
        ("encoder_model.onnx", "encoder_model_quantized.onnx"),
        ("decoder_model.onnx", "decoder_model_quantized.onnx"),
        ("decoder_with_past_model.onnx",
         "decoder_with_past_model_quantized.onnx"),
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

    print("== Verifying quantized graphs ==", flush=True)
    import onnxruntime as ort

    specs = []
    for p in sorted(OUT.iterdir()):
        if not p.is_file():
            continue
        size = p.stat().st_size
        digest = sha256(p)
        specs.append({"file": p.name, "size": size, "sha256": digest})
        notice("PACKSPEC", f"{p.name} size={size} sha256={digest}")
        if p.suffix == ".onnx":
            sess = ort.InferenceSession(
                str(p), providers=["CPUExecutionProvider"])
            ins = [i.name for i in sess.get_inputs()]
            outs = [o.name for o in sess.get_outputs()]
            notice("PACKIO", f"{p.name} inputs={ins} outputs={outs}")

    (OUT / "pack.json").write_text(json.dumps(
        {"model": MODEL_ID, "revision": MODEL_REV, "files": specs},
        indent=2))
    total = sum(s["size"] for s in specs)
    notice("PACKTOTAL", f"total_bytes={total}")
    print(f"Done. Total pack size: {total / 1e6:.1f} MB", flush=True)


if __name__ == "__main__":
    main()
