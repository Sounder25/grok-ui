#!/usr/bin/env python3
"""
Exports Kokoro-82M to the ONNX + asset layout this app expects, and quantizes it to
int8. Run on a dev machine with internet access and Python (not inside the Android build
or this sandbox) -- see docs/BUILD.md.

This intentionally does not reimplement Kokoro's model-export graph tracing; that logic
already exists and is maintained upstream (hexgrad/Kokoro-82M on Hugging Face, and the
kokoro-onnx project's export tooling). This script drives that tooling and then re-shapes
its output into exactly the three asset files KokoroTtsEngine/PhonemeTokenizer/
VoiceStyleRepository expect:

    app/src/main/assets/models/kokoro-82m-int8.onnx
    app/src/main/assets/models/tokenizer.json
    app/src/main/assets/models/voices/<voice_id>.bin   (one per voice)

Requires (pip): torch, onnx, onnxruntime, onnxruntime-tools (or `optimum[onnxruntime]`),
kokoro (`pip install kokoro`), huggingface_hub.

Usage:
    python scripts/export_kokoro_onnx.py --voices af_heart,am_adam --out app/src/main/assets/models
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

STYLE_DIM = 256
MAX_TOKENS = 510


def export_fp32_onnx(out_dir: Path) -> Path:
    """Traces Kokoro-82M and exports a dynamic-axis ONNX graph with
    inputs (tokens:int64[1,N], style:float32[1,256], speed:float32[1]) and
    output (audio:float32[1,S])."""
    try:
        import torch
        from kokoro import KModel  # https://pypi.org/project/kokoro/
    except ImportError as exc:
        sys.exit(
            f"Missing dependency: {exc}. Install with:\n"
            "  pip install torch kokoro onnx onnxruntime huggingface_hub"
        )

    model = KModel().eval()

    dummy_tokens = torch.zeros((1, 32), dtype=torch.int64)
    dummy_style = torch.zeros((1, STYLE_DIM), dtype=torch.float32)
    dummy_speed = torch.ones((1,), dtype=torch.float32)

    fp32_path = out_dir / "kokoro-82m-fp32.onnx"
    torch.onnx.export(
        model,
        (dummy_tokens, dummy_style, dummy_speed),
        fp32_path.as_posix(),
        input_names=["tokens", "style", "speed"],
        output_names=["audio"],
        dynamic_axes={
            "tokens": {1: "sequence_length"},
            "audio": {1: "num_samples"},
        },
        opset_version=17,
    )
    return fp32_path


def quantize_int8(fp32_path: Path, out_path: Path) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(
        model_input=fp32_path.as_posix(),
        model_output=out_path.as_posix(),
        weight_type=QuantType.QInt8,
    )


def export_tokenizer(out_path: Path) -> None:
    from kokoro import KPipeline

    vocab = KPipeline.VOCAB if hasattr(KPipeline, "VOCAB") else KPipeline().vocab
    out_path.write_text(json.dumps(vocab, ensure_ascii=False, indent=0))


def export_voice(voice_id: str, out_path: Path) -> None:
    """Kokoro ships each voice as a torch tensor of shape [510, 1, 256] (or similar);
    this flattens it to the raw little-endian float32[MAX_TOKENS * STYLE_DIM] blob
    VoiceStyleRepository.kt reads directly via ByteBuffer, with no header."""
    import torch
    from huggingface_hub import hf_hub_download

    path = hf_hub_download(repo_id="hexgrad/Kokoro-82M", filename=f"voices/{voice_id}.pt")
    tensor = torch.load(path, map_location="cpu").float()
    tensor = tensor.reshape(MAX_TOKENS, STYLE_DIM)
    assert tensor.shape == (MAX_TOKENS, STYLE_DIM), tensor.shape

    with open(out_path, "wb") as f:
        f.write(struct.pack(f"<{tensor.numel()}f", *tensor.flatten().tolist()))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--voices", default="af_heart", help="Comma-separated voice IDs to bundle")
    parser.add_argument("--out", default="app/src/main/assets/models", help="Output assets dir")
    args = parser.parse_args()

    out_dir = Path(args.out)
    (out_dir / "voices").mkdir(parents=True, exist_ok=True)

    print("[1/4] Tracing + exporting fp32 ONNX graph...")
    fp32_path = export_fp32_onnx(out_dir)

    print("[2/4] Quantizing to int8...")
    quantize_int8(fp32_path, out_dir / "kokoro-82m-int8.onnx")
    fp32_path.unlink(missing_ok=True)

    print("[3/4] Exporting phoneme tokenizer vocab...")
    export_tokenizer(out_dir / "tokenizer.json")

    print("[4/4] Exporting voice style tables...")
    for voice_id in args.voices.split(","):
        voice_id = voice_id.strip()
        print(f"  - {voice_id}")
        export_voice(voice_id, out_dir / "voices" / f"{voice_id}.bin")

    print(f"Done. Assets written to {out_dir}/")


if __name__ == "__main__":
    main()
