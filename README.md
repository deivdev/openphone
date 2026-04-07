# OpenPhone

A human layer over your phone, powered by LLM.

OpenPhone is an AI agent that understands your phone's screen and executes actions on your behalf — tap, swipe, type, open apps — all from a natural language prompt. It runs as a floating overlay on top of other apps, so it can see and interact with whatever is on screen.

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
- **Local LLM**: llama.cpp via NDK, runs Gemma GGUF models on-device
- **Cloud LLM**: Groq API (Llama 3.3 70B, Gemma2, Mixtral)
- Android Accessibility Service for UI tree reading and gesture dispatch

```bash
cd app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Setup

1. Open the app and configure your LLM:
   - **Local**: pick a GGUF model file (e.g. Gemma 4 E4B Q4_K_M, ~4.6GB)
   - **Groq**: paste your API key and select a model
2. Enable the Accessibility Service in Android Settings
3. Grant "Display over other apps" permission
4. Tap **Launch Overlay** — the app minimizes and a floating panel appears
5. Type a command in the overlay and tap **Run**

The overlay auto-collapses while the agent works, then expands to show results. Drag it by the header, collapse with **—**, close with **✕**.

## Stack

- Python, ADB, Groq API (prototype)
- Kotlin, Jetpack Compose, llama.cpp, Android Accessibility Service (app)
