#!/usr/bin/env python3
"""
OpenPhone Agent — Controls your Android phone via natural language prompts.
Uses ADB for UI tree extraction/interaction and a local LLM for reasoning.
"""

import subprocess
import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET

from dotenv import load_dotenv
from groq import Groq

load_dotenv()

MODEL = "llama-3.3-70b-versatile"
client = Groq(api_key=os.environ.get("GROQ_API_KEY"))
MAX_STEPS = 20


def adb(cmd: str, timeout: int = 10) -> str:
    """Run an ADB command and return stdout."""
    result = subprocess.run(
        ["adb"] + cmd.split(),
        capture_output=True, text=True, timeout=timeout
    )
    return result.stdout.strip()


def get_screen_size() -> tuple[int, int]:
    """Get the phone's screen resolution."""
    output = adb("shell wm size")
    match = re.search(r"(\d+)x(\d+)", output)
    if match:
        return int(match.group(1)), int(match.group(2))
    return 1080, 2400


def dump_ui() -> str:
    """Dump UI hierarchy and return simplified element list."""
    adb("shell uiautomator dump /sdcard/ui.xml")
    xml_str = adb("shell cat /sdcard/ui.xml")
    return parse_ui_tree(xml_str)


def parse_ui_tree(xml_str: str) -> str:
    """Parse UI XML into a simplified, LLM-friendly element list."""
    try:
        root = ET.fromstring(xml_str)
    except ET.ParseError:
        return "[ERROR: Could not parse UI tree]"

    elements = []
    idx = 0

    for node in root.iter("node"):
        text = node.get("text", "")
        desc = node.get("content-desc", "")
        cls = node.get("class", "")
        clickable = node.get("clickable") == "true"
        enabled = node.get("enabled") == "true"
        bounds = node.get("bounds", "")
        resource_id = node.get("resource-id", "")
        checkable = node.get("checkable") == "true"
        checked = node.get("checked") == "true"
        focused = node.get("focused") == "true"
        scrollable = node.get("scrollable") == "true"

        # Skip invisible/empty elements
        if not text and not desc and not clickable and not scrollable:
            continue

        # Parse bounds [x1,y1][x2,y2]
        bounds_match = re.findall(r'\[(\d+),(\d+)\]', bounds)
        if len(bounds_match) != 2:
            continue

        x1, y1 = int(bounds_match[0][0]), int(bounds_match[0][1])
        x2, y2 = int(bounds_match[1][0]), int(bounds_match[1][1])
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2

        # Build element description
        label = text or desc or ""
        short_cls = cls.split(".")[-1] if cls else ""

        props = []
        if clickable:
            props.append("clickable")
        if scrollable:
            props.append("scrollable")
        if checkable:
            props.append(f"checked={checked}")
        if focused:
            props.append("focused")

        rid = resource_id.split("/")[-1] if resource_id else ""

        elem = f"[{idx}] {short_cls}"
        if label:
            elem += f' "{label}"'
        if rid:
            elem += f" ({rid})"
        if props:
            elem += f" [{', '.join(props)}]"
        elem += f" @ ({cx},{cy})"

        elements.append(elem)
        idx += 1

    return "\n".join(elements) if elements else "[No UI elements found]"


def tap(x: int, y: int):
    print(f"  → tap({x}, {y})")
    adb(f"shell input tap {x} {y}")


def swipe(x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300):
    print(f"  → swipe({x1},{y1} → {x2},{y2})")
    adb(f"shell input swipe {x1} {y1} {x2} {y2} {duration_ms}")


def type_text(text: str):
    print(f'  → type("{text}")')
    escaped = text.replace(" ", "%s").replace("&", "\\&").replace("(", "\\(").replace(")", "\\)")
    adb(f"shell input text {escaped}")


def press_key(key: str):
    keymap = {
        "home": "KEYCODE_HOME",
        "back": "KEYCODE_BACK",
        "enter": "KEYCODE_ENTER",
        "recent": "KEYCODE_APP_SWITCH",
    }
    keycode = keymap.get(key.lower(), f"KEYCODE_{key.upper()}")
    print(f"  → key({keycode})")
    adb(f"shell input keyevent {keycode}")


def open_app(package_or_name: str):
    """Try to open an app by package name or search for it."""
    print(f"  → open_app({package_or_name})")
    # Try direct launch by common package names
    common = {
        "whatsapp": "com.whatsapp",
        "chrome": "com.android.chrome",
        "clock": "com.google.android.deskclock",
        "camera": "com.nothing.camera",
        "settings": "com.android.settings",
        "phone": "com.android.dialer",
        "messages": "com.google.android.apps.messaging",
        "gmail": "com.google.android.gm",
        "maps": "com.google.android.apps.maps",
        "youtube": "com.google.android.youtube",
        "calendar": "com.google.android.calendar",
        "contacts": "com.google.android.contacts",
        "calculator": "com.google.android.calculator",
        "photos": "com.google.android.apps.photos",
        "telegram": "org.telegram.messenger",
    }
    pkg = common.get(package_or_name.lower(), package_or_name)
    result = adb(f"shell monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
    if "No activities found" not in result:
        return True
    return False


SYSTEM_PROMPT = """You are a phone automation agent. You control an Android phone to accomplish tasks.

GOAL: {goal}

You see a list of UI elements currently on screen. Each element has:
- [index] WidgetType "label" (resource_id) [properties] @ (tap_x, tap_y)

Choose ONE action. Respond with ONLY a JSON object:

{{"action": "tap", "x": <int>, "y": <int>, "reason": "..."}}
{{"action": "type", "text": "...", "reason": "..."}}
{{"action": "swipe_up", "reason": "..."}}
{{"action": "swipe_down", "reason": "..."}}
{{"action": "key", "key": "home|back|enter", "reason": "..."}}
{{"action": "open_app", "app": "<app_name>", "reason": "..."}}
{{"action": "done", "reason": "..."}}
{{"action": "fail", "reason": "..."}}

Rules:
- Use "open_app" to launch apps (whatsapp, chrome, clock, etc.)
- Use tap coordinates from the element list (the @ values)
- Type text only when an input field is focused
- Press "back" to go back, "home" for home screen
- Say "done" when the goal is achieved
- Say "fail" if impossible

Respond with ONLY the JSON, no other text."""


def ask_llm(ui_elements: str, goal: str, history: list[str]) -> str:
    """Ask the LLM to decide the next action."""
    prompt = SYSTEM_PROMPT.format(goal=goal)

    history_text = ""
    if history:
        history_text = "\n\nPrevious actions:\n" + "\n".join(history[-5:])

    user_msg = f"Current screen elements:\n{ui_elements}{history_text}\n\nWhat is the next action?"

    response = client.chat.completions.create(
        model=MODEL,
        messages=[
            {"role": "system", "content": prompt},
            {"role": "user", "content": user_msg},
        ],
        temperature=0,
    )
    return response.choices[0].message.content


def parse_action(response: str) -> dict | None:
    """Parse the LLM response into an action dict."""
    # Try to find JSON in the response (skip <think> tags from deepseek)
    cleaned = re.sub(r'<think>.*?</think>', '', response, flags=re.DOTALL)

    # Find all JSON-like blocks
    matches = re.findall(r'\{[^{}]+\}', cleaned)
    for match in matches:
        try:
            parsed = json.loads(match)
            if "action" in parsed:
                return parsed
        except json.JSONDecodeError:
            continue
    return None


def execute_action(action: dict, width: int, height: int) -> str:
    """Execute a parsed action. Returns description for history."""
    act = action.get("action", "")
    reason = action.get("reason", "")

    if act == "tap":
        x = min(max(int(action["x"]), 0), width)
        y = min(max(int(action["y"]), 0), height)
        tap(x, y)
        return f"Tapped ({x},{y}): {reason}"

    elif act == "type":
        text = action["text"]
        type_text(text)
        return f'Typed "{text}": {reason}'

    elif act == "swipe_up":
        cx = width // 2
        swipe(cx, height * 3 // 4, cx, height // 4)
        return f"Swiped up: {reason}"

    elif act == "swipe_down":
        cx = width // 2
        swipe(cx, height // 4, cx, height * 3 // 4)
        return f"Swiped down: {reason}"

    elif act == "key":
        press_key(action["key"])
        return f"Pressed {action['key']}: {reason}"

    elif act == "open_app":
        app = action["app"]
        open_app(app)
        return f"Opened {app}: {reason}"

    elif act in ("done", "fail"):
        return ""

    return f"Unknown action: {act}"


def run_agent(goal: str):
    """Main agent loop."""
    width, height = get_screen_size()
    print(f"\n📱 Screen: {width}x{height}")
    print(f"🎯 Goal: {goal}\n")

    history = []

    for step in range(1, MAX_STEPS + 1):
        print(f"--- Step {step}/{MAX_STEPS} ---")

        # Dump UI
        print("  📋 Reading screen...")
        ui = dump_ui()
        # Show condensed UI
        lines = ui.split("\n")
        print(f"  📋 {len(lines)} elements found")
        for line in lines[:10]:
            print(f"     {line}")
        if len(lines) > 10:
            print(f"     ... and {len(lines) - 10} more")

        # Ask LLM
        print("  🤖 Thinking...")
        t = time.time()
        response = ask_llm(ui, goal, history)
        elapsed = time.time() - t
        print(f"  ⏱️  {elapsed:.1f}s")

        # Parse
        action = parse_action(response)
        if not action:
            print(f"  [!] Could not parse action from response")
            print(f"  Raw: {response[:200]}")
            history.append("Failed to parse action")
            continue

        act = action["action"]
        reason = action.get("reason", "")
        print(f"  🎯 {act}: {reason}")

        if act == "done":
            print(f"\n✅ Task completed: {reason}")
            return True
        if act == "fail":
            print(f"\n❌ Task failed: {reason}")
            return False

        # Execute
        desc = execute_action(action, width, height)
        history.append(desc)
        time.sleep(1.5)

    print(f"\n⚠️  Max steps ({MAX_STEPS}) reached")
    return False


def main():
    if len(sys.argv) > 1:
        goal = " ".join(sys.argv[1:])
    else:
        goal = input("🎯 What should I do on your phone? > ").strip()
        if not goal:
            print("No goal provided.")
            return

    run_agent(goal)


if __name__ == "__main__":
    main()
