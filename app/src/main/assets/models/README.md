# Model assets (not checked in)

This directory is populated by `scripts/export_kokoro_onnx.py` (see `docs/BUILD.md`). It
is intentionally empty in version control — the quantized model and voice style tables
are tens of megabytes of binary data that don't belong in git history.

Expected layout after running the export script:

```
models/
├── kokoro-82m-int8.onnx   # int8-quantized Kokoro-82M, dynamic axes on sequence length
├── tokenizer.json         # {"symbol": id} IPA phoneme -> vocab id, from the same export
└── voices/
    ├── af_heart.bin        # float32[510, 256] style table, one file per voice
    ├── am_adam.bin
    └── ...
```

`KokoroTtsEngine` (see `app/src/main/java/.../engine/`) reads these three inputs; the
`.onnx`/`.bin` files are gitignored, `tokenizer.json` and this README are not.
