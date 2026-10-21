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
- **Local LLM only**: Gemma 4 (E2B / E4B) on LiteRT-LM, Google's on-device runtime, GPU-accelerated with CPU fallback. Nothing leaves the phone
- Models downloaded in-app from Hugging Face, loaded on first use and unloaded when idle
- Android Accessibility Service for UI tree reading and gesture dispatch

```bash
cd app
JAVA_HOME=~/bin/android-studio/jbr ./gradlew assembleDebug   # JDK 17–21; Kotlin 2.4, AGP 9
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Setup

1. Open the app and tap **Get** on a model ([Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) recommended, or [E4B](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)), or import a `.litertlm` file you already have
2. Grant the Accessibility Service
3. Grant "Display over other apps"
4. Tap **Launch Overlay** — the app minimizes and a floating panel appears
5. Type a command in the overlay and tap **Run**

The overlay auto-collapses while the agent works, then expands to show results. Drag it by the header, collapse with **—**, close with **✕**.

## Stack

- Python, ADB, Groq API (prototype)
- Kotlin, Jetpack Compose, LiteRT-LM (Gemma 4), Android Accessibility Service (app)
