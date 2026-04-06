# OpenPhone

A human layer over your phone, powered by LLM.

OpenPhone is an AI agent that understands your phone's screen and executes actions on your behalf — tap, swipe, type, open apps — all from a natural language prompt. Think of it as a pair of hands guided by an LLM that can see what's on your screen.

## How it works

1. Read the screen (UI tree)
2. Send it to an LLM for reasoning
3. Execute the chosen action (tap, swipe, type, open app, etc.)
4. Repeat until the goal is done

## Two implementations

### `agent.py` — Python prototype

Desktop script that controls an Android phone over ADB. Quick to hack on, requires a USB-connected device.

- ADB for screen reading (`uiautomator dump`) and actions (`input tap/swipe/text`)
- Groq API (Llama 3.3 70B) for reasoning

```bash
pip install groq python-dotenv
# Create .env with GROQ_API_KEY
python agent.py "open chrome and search for weather"
```

### `app/` — Android app

Native app that runs entirely on the phone. Uses the Accessibility Service to read and control the UI — no ADB, no USB, no PC needed.

- Kotlin + Jetpack Compose
- **Local LLM**: llama.cpp via NDK, runs Gemma GGUF models on-device
- **Cloud LLM**: Groq API (Llama 3.3 70B, Gemma2, Mixtral)
- Android Accessibility Service for UI automation

```bash
cd app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires:
- Download a GGUF model (e.g. Gemma 4 E4B Q4_K_M) for local mode, or a Groq API key for cloud mode
- Enable the Accessibility Service in Android Settings

## Stack

- Python, ADB, Groq API (prototype)
- Kotlin, Jetpack Compose, llama.cpp, Android Accessibility Service (app)
