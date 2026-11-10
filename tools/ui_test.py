#!/usr/bin/env python3
"""Drives the keyboard on a running emulator/device and checks it end to end.

  python3 tools/ui_test.py app/build/outputs/apk/debug/app-debug.apk out_dir

It installs the debug APK, makes NeuralKeyboard the current keyboard, opens the
app's test box and then, by tapping real keys:
  - checks the keyboard's height is sane and never changes between English,
    Thai, the symbol pages and the clipboard / snippet panels
  - types "teh" + space and expects the field to contain "the" (auto-correct)
  - types Thai and expects it to arrive in the field
  - fails if the keyboard process crashed
A screenshot of every state is written to out_dir (uploaded by CI).

Debug builds log where each key is on screen (tag NKTEST); that is how this
script knows where to tap.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.neuralkeyboard.ime"
IME = f"{PKG}/.SmartKeyboardService"

failures = []
notes = []


def adb(*args, timeout=120, binary=False):
    out = subprocess.run(["adb", *args], capture_output=True, timeout=timeout).stdout
    return out if binary else out.decode("utf-8", "replace")


def check(ok, message):
    (notes if ok else failures).append(("PASS " if ok else "FAIL ") + message)
    print(("PASS " if ok else "FAIL ") + message, flush=True)


class Keyboard:
    """Latest layout reported by the app."""

    def __init__(self):
        self.keys = {}
        self.buttons = {}
        self.root_heights = []
        self.view_heights = []

    def refresh(self):
        log = adb("logcat", "-d", "-v", "raw", "-s", "NKTEST:I")
        self.root_heights = [int(h) for h in re.findall(r"^root top=-?\d+ height=(\d+)", log, re.M)]
        self.view_heights = [int(h) for h in re.findall(r"^view top=-?\d+ height=(\d+)", log, re.M)]
        for name, x, y in re.findall(r"^button (\w+) (\d+),(\d+)", log, re.M):
            self.buttons[name] = (int(x), int(y))
        lines = [l for l in log.splitlines() if l.startswith("keys\u241e")]
        if lines:
            self.keys = {}
            for entry in lines[-1].split("\u241e")[1:]:
                name, pos = entry.split("\u241f")
                x, y = pos.split(",")
                self.keys[name] = (int(x), int(y))
        return self

    def tap(self, name, pause=0.7):
        self.refresh()
        pos = self.keys.get(name) or self.keys.get(name.upper()) or self.keys.get(name.lower())
        if pos is None:
            check(False, f"key {name!r} not on the keyboard (have: {' '.join(self.keys)})")
            return
        adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
        time.sleep(pause)

    def tap_button(self, name, pause=1.5):
        self.refresh()
        pos = self.buttons.get(name)
        if pos is None:
            check(False, f"toolbar button {name!r} not found")
            return
        adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
        time.sleep(pause)


def ui_dump():
    adb("shell", "uiautomator", "dump", "/sdcard/nk_ui.xml")
    xml = adb("shell", "cat", "/sdcard/nk_ui.xml")
    try:
        return ET.fromstring(xml[xml.index("<?xml"):])
    except (ValueError, ET.ParseError):
        return None


def edit_text():
    root = ui_dump()
    if root is None:
        return None
    for node in root.iter("node"):
        if node.get("class") == "android.widget.EditText" and node.get("package") == PKG:
            return node
    return None


def field_text():
    node = edit_text()
    return node.get("text", "") if node is not None else ""


def main(apk, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    def shot(name):
        (out / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))

    print(adb("install", "-r", apk, timeout=300))
    adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    print(adb("shell", "ime", "enable", IME), adb("shell", "ime", "set", IME))
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.SettingsActivity")
    time.sleep(5)

    size = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1]
    screen_h = int(size[1])

    node = edit_text()
    if node is None:
        shot("00_no_edit_text")
        check(False, "test box (EditText) not found in the settings screen")
        return
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    kb = Keyboard()
    for _ in range(30):
        time.sleep(1)
        if kb.refresh().keys:
            break
    shot("01_english")
    if not kb.keys:
        check(False, "keyboard did not appear (no key layout reported)")
        return

    check(bool(kb.root_heights), "keyboard reported its size")
    if kb.root_heights:
        h = kb.root_heights[-1]
        check(0.2 * screen_h < h < 0.6 * screen_h,
              f"keyboard height {h}px is between 20% and 60% of the {screen_h}px screen")
    check("1" in kb.keys and "q" in {k.lower() for k in kb.keys}, "English layout has a number row and letters")

    time.sleep(12)  # dictionaries load in the background

    for key in ["t", "e", "h", "SPACE"]:
        kb.tap(key)
    time.sleep(1)
    shot("02_typed_english")
    text = field_text()
    check(text.strip().lower() == "the", f'typing "teh" + space gives "the" (field: {text!r})')

    kb.tap("LANGUAGE", pause=2)
    shot("03_thai")
    kb.refresh()
    check("ก" in kb.keys and "ๅ" in kb.keys, "Thai layout is shown after the language key")
    for key in ["ส", "ว", "ั", "ส", "ด", "ี", "ค"]:
        kb.tap(key)
    time.sleep(1)
    shot("04_typed_thai")
    text = field_text()
    check("สวัสดีค" in text, f"Thai typing arrives in the field (field: {text!r})")

    kb.tap("SYMBOLS", pause=1.5)
    shot("05_symbols")
    kb.refresh()
    check("@" in kb.keys and "SYMBOLS_MORE" in kb.keys, "symbols page is shown")
    kb.tap("SYMBOLS_MORE", pause=1.5)
    shot("06_symbols_2")
    kb.tap("LETTERS", pause=1.5)

    kb.tap_button("clipboard")
    shot("07_clipboard_panel")
    kb.tap_button("clipboard")
    kb.tap_button("snippet")
    shot("08_snippet_panel")
    kb.tap_button("snippet")
    kb.tap("LANGUAGE", pause=2)
    shot("09_back_to_english")

    kb.refresh()
    check(len(set(kb.root_heights)) == 1,
          f"keyboard height never changed across layouts and panels (seen: {sorted(set(kb.root_heights))})")
    check(len(set(kb.view_heights)) == 1,
          f"key area height never changed (seen: {sorted(set(kb.view_heights))})")

    log = adb("logcat", "-d")
    crashed = f"Process: {PKG}" in log
    check(not crashed, "keyboard process did not crash")
    if crashed:
        i = log.index(f"Process: {PKG}")
        (out / "crash.txt").write_text(log[max(0, i - 500): i + 6000])


if __name__ == "__main__":
    try:
        main(sys.argv[1], sys.argv[2])
    except Exception as e:  # report, don't hide
        check(False, f"test script error: {e!r}")
    report = "\n".join(notes + failures) + f"\n\n{len(notes)} passed, {len(failures)} failed\n"
    Path(sys.argv[2]).mkdir(parents=True, exist_ok=True)
    (Path(sys.argv[2]) / "report.txt").write_text(report)
    print(report)
    sys.exit(1 if failures else 0)
