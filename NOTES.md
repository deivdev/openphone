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

- [ ] Test llama.cpp as local alternative
- [ ] Explore Accessibility Service prototype
- [ ] Add interactive/chat mode
- [ ] Screenshot-based vision for richer context
