"""Tiny UI driver for the emulator test: find a node by text / description and tap it."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args, **kw):
    return subprocess.run(["adb", *args], capture_output=True, **kw)


def dump():
    for _ in range(5):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        out = adb("exec-out", "cat", "/sdcard/ui.xml").stdout
        try:
            return ET.fromstring(out)
        except ET.ParseError:
            time.sleep(1)
    return ET.fromstring("<hierarchy/>")


def find(label):
    for n in dump().iter("node"):
        if label in (n.get("text") or "") or label == (n.get("content-desc") or ""):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def main():
    cmd, label = sys.argv[1], sys.argv[2]
    if cmd == "has":
        found = find(label) is not None
        print(f"ui.py: '{label}' {'is' if found else 'is not'} on screen")
        sys.exit(0 if found else 1)
    for attempt in range(6):
        pos = find(label)
        if pos:
            break
        if cmd in ("tap-scroll", "long-scroll"):
            adb("shell", "input", "swipe", "540", "1700", "540", "700", "250")
        time.sleep(1)
    if not pos:
        print(f"ui.py: '{label}' not found")
        sys.exit(1)
    x, y = pos
    if cmd.startswith("long"):
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
    elif cmd.startswith("tap"):
        adb("shell", "input", "tap", str(x), str(y))
    print(f"ui.py: {cmd} '{label}' at {x},{y}")


if __name__ == "__main__":
    main()
