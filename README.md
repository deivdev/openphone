# OpenPhone

### A new way to interact with our phones.

We tap, swipe, scroll, type — hundreds of times a day, every day. What if you could just *say what you want* and your phone does it?

OpenPhone is an AI layer that sits between you and your phone. It sees your screen, understands it, and acts on it — just like you would, but from a single sentence. No custom APIs, no app integrations, no shortcuts to configure. It works with *any* app, *any* screen, because it interacts with your phone the same way you do: by looking and tapping.

> "Find the last highlights video on YouTube and play it"

That's it. That's the input. The agent reads the screen, reasons about what to do next, taps the right buttons, types in search bars, scrolls through results — step by step until the job is done.

Local LLM on your device, or cloud. Your choice. Your data.

## How it works

1. Read the screen (UI tree via Accessibility Service)
2. Send it to an LLM (local or cloud) for reasoning
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

Native app that runs entirely on the phone — no ADB, no USB, no PC needed.

- Kotlin + Jetpack Compose for configuration
- Floating overlay panel for running the agent on top of other apps
- **Local LLM**, two engines picked by model file extension:
  - LiteRT-LM (`.litertlm`/`.task`) — Google's on-device runtime, GPU-accelerated with CPU fallback
  - llama.cpp via NDK (`.gguf`) — CPU only
- **Cloud LLM**: Groq API (Llama 3.3 70B, Gemma2, Mixtral)
- Android Accessibility Service for UI tree reading and gesture dispatch

```bash
cd app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Setup

1. Open the app and configure your LLM:
   - **Local**: pick a model file — `.litertlm` recommended (e.g. [gemma-4-E4B-it](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) or [gemma-3n-E4B-it-int4](https://huggingface.co/google/gemma-3n-E4B-it-litert-lm)); `.gguf` files still work via llama.cpp on CPU
   - **Groq**: paste your API key and select a model
2. Enable the Accessibility Service in Android Settings
3. Grant "Display over other apps" permission
4. Tap **Launch Overlay** — the app minimizes and a floating panel appears
5. Type a command in the overlay and tap **Run**

The overlay auto-collapses while the agent works, then expands to show results. Drag it by the header, collapse with **—**, close with **✕**.

## Stack

- Python, ADB, Groq API (prototype)
- Kotlin, Jetpack Compose, LiteRT-LM, llama.cpp, Android Accessibility Service (app)
