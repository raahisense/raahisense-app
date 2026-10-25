#!/usr/bin/env python3
"""Copy Android phrase files into iOS Localizable.strings.

The Android XML stays the source of the spoken words. %1$s becomes %@ so
String(format:) can fill in the object name. Run this before an iOS build.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
OUT = ROOT / "ios" / "RaahiSense" / "Resources"
LOCALES = {
    "values": "en.lproj",
    "values-hi": "hi.lproj",
    "values-ta": "ta.lproj",
    "values-bn": "bn.lproj",
    "values-sw": "sw.lproj",
    "values-es": "es.lproj",
}
ENTRY = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.DOTALL)

# iPhone-only sentences. English until a native speaker reviews them.
IOS_EXTRAS = {
    "listen_button": "Listen",
    "cd_listen": "Listen for one command. You do not need to say Hey Guide first.",
    "save_wake_phrase": "Save wake phrase",
    "label_volume": "Speech volume",
    "volume_quiet": "Quiet",
    "volume_medium": "Medium",
    "volume_loud": "Loud",
    "ios_screen_stays_on": "The screen stays on while guiding so the camera can keep working. Locking the screen stops the camera.",
    "ios_limit_locked": "The camera stopped because the screen locked or RaahiSense left the screen. Open RaahiSense again. On iPhone the camera cannot keep running with the screen locked.",
    "ios_limit_light": "This iPhone has no light sensor the app can read. Darkness is judged from the camera picture, and from ISO and exposure time when the camera provides them.",
    "ios_limit_wake": "Hey Guide uses the iPhone on-device English speech recognizer. It is not the Android Vosk model. The Vosk iOS library is not public. If on-device recognition is not installed, warnings still work, and you can use the large Listen button after recognition is turned on.",
    "ios_limit_volume": "Holding a volume key does not start listening on iPhone. Say Hey Guide, tap Listen, double-tap this screen when VoiceOver is off, or shake the phone hard twice.",
    "ios_about_models": "The obstacle model and the depth model are the same files as the Android app. They run on the phone with TensorFlow Lite. They are not converted to Core ML, so the warnings stay the same. Nothing is uploaded.",
    "ios_prepared": "Prepared for Dr. Chindanoor Rajesh.",
    "status_speech_unavailable": "On-device English speech recognition is not available on this iPhone. Turn it on in Settings while the phone is online once. Until then, use the buttons. Obstacle warnings still work without the microphone.",
}


def unescape(value: str) -> str:
    return (
        value.replace("\\'", "'")
        .replace('\\"', '"')
        .replace("&apos;", "'")
        .replace("&quot;", '"')
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("%1$s", "%@")
        .replace("%2$s", "%@")
        .strip()
    )


def escape_strings(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def main() -> None:
    for folder, lproj in LOCALES.items():
        xml = (RES / folder / "strings.xml").read_text(encoding="utf-8")
        pairs = [(name, unescape(body)) for name, body in ENTRY.findall(xml)]
        names = {name for name, _ in pairs}
        for name, value in IOS_EXTRAS.items():
            if name not in names:
                pairs.append((name, value))
        dest = OUT / lproj
        dest.mkdir(parents=True, exist_ok=True)
        lines = [
            "/* Generated from Android strings.xml. Do not edit by hand. */",
            "",
        ]
        for name, value in pairs:
            lines.append(f'"{name}" = "{escape_strings(value)}";')
        # Xcode accepts UTF-16 .strings files without guessing the encoding.
        (dest / "Localizable.strings").write_text("\n".join(lines) + "\n", encoding="utf-16")
        print(f"{lproj}: {len(pairs)} strings")


if __name__ == "__main__":
    main()
