"""Diagnostic 2: find a quantization scheme whose math matches fp32
on every CPU. Candidates from one fp32 export of opus-mt-en-ine:
  QUInt8 dynamic (Xenova-style) and W8A32 weight-only (int8 weights
  dequantized to fp32 inside the graph -> fp32 GEMM everywhere)."""
import json
import subprocess
import time
from pathlib import Path

import numpy as np

PAD_ID = 61160
EOS_ID = 0
TEXT = "Where are you going? I will wait for you at the station."
TEXT3 = "Don't touch my sister, or I will kill you."


def notice(title, msg):
    msg = str(msg).replace("%", "%25").replace("\r", " ").replace("\n", " ⏎ ")
    print(f"::notice title={title}::{msg[:950]}", flush=True)


def make_w8a32(src_path, dst_path):
    """Rewrite MatMul/Gemm/Gather weight initializers to int8 +
    DequantizeLinear, keeping all compute in fp32."""
    import onnx
    from onnx import TensorProto, helper, numpy_helper

    model = onnx.load(str(src_path))
    g = model.graph
    inits = {i.name: i for i in g.initializer}

    def quantize(name, axis):
        w = numpy_helper.to_array(inits[name]).astype(np.float32)
        if axis is None:  # per-tensor
            scale = np.float32(np.abs(w).max() / 127.0 + 1e-12)
            q = np.round(w / scale).clip(-128, 127).astype(np.int8)
            scales = np.array(scale, dtype=np.float32)
            zp = np.array(0, dtype=np.int8)
        else:
            m = np.abs(w).max(axis=1 - axis)
            scales = (m / 127.0 + 1e-12).astype(np.float32)
            shape = [1, 1]
            shape[axis] = -1
            q = np.round(w / scales.reshape(shape)).clip(
                -128, 127).astype(np.int8)
            zp = np.zeros_like(scales, dtype=np.int8)
        return q, scales, zp

    # Decide per-initializer strategy from consuming nodes.
    plan = {}  # init name -> axis (None = per-tensor)
    for node in g.node:
        if node.op_type == "MatMul" and len(node.input) > 1 \
                and node.input[1] in inits:
            w = numpy_helper.to_array(inits[node.input[1]])
            if w.ndim == 2 and w.size >= 4096:
                plan[node.input[1]] = 1
        elif node.op_type == "Gather" and node.input[0] in inits:
            w = numpy_helper.to_array(inits[node.input[0]])
            if w.ndim == 2 and w.size >= 4096:
                plan[node.input[0]] = 0
        elif node.op_type == "Gemm" and len(node.input) > 1 \
                and node.input[1] in inits:
            w = numpy_helper.to_array(inits[node.input[1]])
            if w.ndim == 2 and w.size >= 4096 and node.input[1] not in plan:
                trans_b = 0
                for a in node.attribute:
                    if a.name == "transB":
                        trans_b = a.i
                plan[node.input[1]] = 0 if trans_b else 1

    dq_out = {}
    new_inits = []
    dq_nodes = []
    for name, axis in plan.items():
        q, scales, zp = quantize(name, axis)
        qn = name + "_i8"
        sn = name + "_scale"
        zn = name + "_zp"
        outn = name + "_dq"
        qt = numpy_helper.from_array(q, qn)
        st = numpy_helper.from_array(scales, sn)
        zt = numpy_helper.from_array(zp, zn)
        new_inits += [qt, st, zt]
        attrs = {"axis": axis} if axis is not None else {}
        dq_nodes.append(helper.make_node(
            "DequantizeLinear", [qn, sn, zn], [outn],
            name=name + "_dqnode", **attrs))
        dq_out[name] = outn

    for node in g.node:
        for i, inp in enumerate(node.input):
            if inp in dq_out:
                node.input[i] = dq_out[inp]
    # Drop replaced initializers, add new ones, prepend DQ nodes.
    keep = [i for i in g.initializer if i.name not in dq_out]
    del g.initializer[:]
    g.initializer.extend(keep + new_inits)
    for i, n in enumerate(dq_nodes):
        g.node.insert(i, n)
    # DequantizeLinear needs opset >= 13 (axis attr) - export used 14.
    onnx.save(model, str(dst_path))
    return len(dq_out)


def main():
    import onnxruntime as ort
    import sentencepiece as spm
    from onnxruntime.quantization import QuantType, quantize_dynamic

    proc = subprocess.run(
        ["optimum-cli", "export", "onnx", "--model",
         "Helsinki-NLP/opus-mt-en-ine", "--task", "translation",
         "--opset", "14", "--atol", "1e-3", "fp32"],
        capture_output=True, text=True)
    if proc.returncode != 0:
        print(proc.stderr[-4000:], flush=True)
        raise SystemExit("export failed")

    Path("quint8").mkdir(exist_ok=True)
    Path("w8a32").mkdir(exist_ok=True)
    for name in ("encoder_model", "decoder_model"):
        quantize_dynamic(f"fp32/{name}.onnx", f"quint8/{name}_q.onnx",
                         op_types_to_quantize=["MatMul", "Gather"],
                         per_channel=True, weight_type=QuantType.QUInt8)
        n = make_w8a32(Path(f"fp32/{name}.onnx"), Path(f"w8a32/{name}_q.onnx"))
        print(f"w8a32 {name}: {n} weights quantized", flush=True)
        for d in ("quint8", "w8a32"):
            p = Path(f"{d}/{name}_q.onnx")
            notice("SIZE", f"{p} {p.stat().st_size}")

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
        t0 = time.time()
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
        return top1, decode(gen), int((time.time() - t0) * 1000)

    for label, enc_p, dec_p in (
            ("QU8", "quint8/encoder_model_q.onnx", "quint8/decoder_model_q.onnx"),
            ("W8", "w8a32/encoder_model_q.onnx", "w8a32/decoder_model_q.onnx")):
        top1, g, ms = greedy(enc_p, dec_p, TEXT)
        notice(f"{label}_TOP1", top1)
        notice(f"{label}_GREEDY", f"[{ms}ms] {g}")
        _, g3, _ = greedy(enc_p, dec_p, TEXT3)
        notice(f"{label}_GREEDY3", g3)
    print("diagnostic 2 done", flush=True)


if __name__ == "__main__":
    main()
