# OpenPhone Agent — Notes

## Architecture

- Python agent that controls an Android phone via natural language
- ADB for UI tree extraction (`uiautomator dump`) and actions (`input tap/swipe/text`)
- LLM (Groq + Llama 3.3 70B) for reasoning and deciding next action
- Agentic loop: read screen → ask LLM → execute action → repeat

## Running LLM locally vs cloud

- Local Ollama on T14s (CPU only, no GPU) is too slow even with qwen2.5:1.5b
- Groq free tier works great — fast inference, good model quality
- llama.cpp is another local option (TODO: test if faster than Ollama on CPU)

## On-device LLM engine (Android app)

- llama.cpp via NDK works but is CPU-only — slow on phone
- MediaPipe LLM Inference (`tasks-genai`) considered, but Google put it in
  maintenance-only mode → chose **LiteRT-LM** (its successor) instead
- LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`, 0.13.1):
  - GPU backend with CPU fallback (`LitertLmBackend` tries GPU first)
  - Models in `.litertlm` format from HuggingFace `litert-community` / `google`
    (gemma-4-E4B-it, gemma-3n-E4B-it-int4) — GGUF not supported
  - Gemma-3n/Gemma-4 are multimodal → path to screenshot-based vision later
  - Requires Kotlin ≥ 2.2 (library metadata is 2.3.0) — project bumped to 2.2.21
- Both engines coexist: file picker routes by extension (`.litertlm`/`.task` →
  LiteRT-LM, `.gguf` → llama.cpp)

## Phone control approaches

| Approach | Requires | Screen must be on | Distributable |
|----------|----------|-------------------|---------------|
| ADB over USB/WiFi | USB debug enabled | Yes | No (dev only) |
| Termux + Shizuku | Shizuku app | Yes | Hacky |
| Accessibility Service | Android app (Kotlin/Java) | No | Yes (Play Store) |
| Termux + Root | Rooted phone | Yes | No |

## Accessibility Service (best path forward)

- Official Android API for UI automation
- No root or ADB needed
- Works with screen off/locked
- Can read UI tree, perform taps/swipes/typing
- Can launch apps and navigate autonomously
- Requires building an Android app in Kotlin/Java
- The proper path for a distributable product

## TODO

- [x] Test llama.cpp as local alternative
- [x] Explore Accessibility Service prototype
- [x] GPU-accelerated local inference (LiteRT-LM)
- [ ] Add interactive/chat mode
- [ ] Screenshot-based vision for richer context (Gemma-3n/4 via LiteRT-LM support image input)
