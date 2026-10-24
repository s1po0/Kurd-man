# Third-party notices

## Meta NLLB-200 distilled 600M

Kurd-man v1.5 can optionally download the INT8 ONNX export of Meta AI's
NLLB-200 distilled 600M model for fully on-device translation to Central
Kurdish (`ckb_Arab`).

- ONNX export: https://huggingface.co/Xenova/nllb-200-distilled-600M
- Base model: https://huggingface.co/facebook/nllb-200-distilled-600M
- Pinned revision: `261c31d1a5732c67cdd16d80e8d6088507c7ccea`
- License: Creative Commons Attribution-NonCommercial 4.0 International
  (CC BY-NC 4.0), https://creativecommons.org/licenses/by-nc/4.0/

The model weights are **not** included in this repository or in the APK.
They are downloaded only at the user's request and stored in the Android
app's private files directory. The CC BY-NC 4.0 terms apply to the model
weights; do not treat them as unrestricted commercial software.

Downloaded files pinned by size and SHA-256 in
`android/app/src/main/java/com/kurdman/app/OfflineModelManager.java`:

- `sentencepiece.bpe.model`
- `onnx/encoder_model_quantized.onnx`
- `onnx/decoder_model_quantized.onnx`
- `onnx/decoder_with_past_model_quantized.onnx`

## InstantVoiceTranslate

The pure-Java SentencePiece BPE and the Android-safe two-decoder NLLB/ONNX
approach were adapted from InstantVoiceTranslate by Iliya Brook.

- Project: https://github.com/IliyaBrook/InstantVoiceTranslate
- License: MIT
- Full license text in the APK:
  `android/app/src/main/assets/licenses/InstantVoiceTranslate-MIT.txt`

## ONNX Runtime

The Android app uses Microsoft ONNX Runtime (`onnxruntime-android`) to run
the downloaded model on the phone.

- Project: https://github.com/microsoft/onnxruntime
- License: MIT
- Full license text in the APK:
  `android/app/src/main/assets/licenses/ONNXRuntime-MIT.txt`
