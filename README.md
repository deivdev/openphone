# OpenPhone

AI agent that controls an Android phone via natural language. Uses ADB for UI interaction and a cloud LLM for reasoning.

## How it works

1. Read screen via `adb shell uiautomator dump`
2. Send UI tree to LLM for reasoning
3. Execute action via `adb shell input tap/swipe/text`
4. Repeat

## Stack

- Python
- ADB (Android Debug Bridge)
- Groq API + Llama 3.3 70B for reasoning

## Setup

```bash
pip install groq python-dotenv
```

Create `.env` with your Groq API key.

## Usage

```bash
python agent.py
```

Requires an Android device connected via ADB.
