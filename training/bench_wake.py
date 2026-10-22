#!/usr/bin/env python3
"""Measure the bundled Vosk wake phrase on this machine.

Speech is espeak-ng. Noise is filtered random noise plus overlapping
synthetic voices. This is not a street recording and not a phone battery test.
"""
from __future__ import annotations

import json
import os
import resource
import subprocess
import tempfile
import time
import wave
from pathlib import Path

import numpy as np
from vosk import KaldiRecognizer, Model

ROOT = Path("/workspace")
MODEL = Path("/tmp/vosk/vosk-model-small-en-us-0.15")
VOCAB = ROOT / "app/src/main/assets/vosk-small-en-words.txt"
OUT = ROOT / "training/metrics/wake_bench.json"
SAMPLE_RATE = 16000
WAKE = "hey guide"
COMMANDS = [
    "start",
    "stop",
    "pause",
    "what is in front of me",
    "what is ahead",
    "repeat",
    "louder",
    "quieter",
    "faster",
    "slower",
    "change language",
    "help",
]


def vocab_words() -> set[str]:
    words = set()
    for line in VOCAB.read_text().splitlines():
        word = line.strip().lower()
        if word:
            words.add(word)
    return words


def allowed(phrase: str, words: set[str]) -> bool:
    return all(part in words for part in phrase.split())


def grammar() -> str:
    words = vocab_words()
    phrases = [WAKE]
    for command in COMMANDS:
        if allowed(command, words):
            phrases.append(f"{WAKE} {command}")
    phrases.append("[unk]")
    return json.dumps(phrases)


def synth(text: str, rate: int, voice: str, dest: Path) -> None:
    subprocess.run(
        ["espeak-ng", "-v", voice, "-s", str(rate), "-w", str(dest), text],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path)) as handle:
        rate = handle.getframerate()
        frames = handle.readframes(handle.getnframes())
        audio = np.frombuffer(frames, dtype=np.int16).astype(np.float32)
        channels = handle.getnchannels()
        if channels > 1:
            audio = audio.reshape(-1, channels).mean(axis=1)
    if rate == SAMPLE_RATE:
        return audio
    length = int(len(audio) * SAMPLE_RATE / rate)
    positions = np.linspace(0, len(audio) - 1, length)
    return np.interp(positions, np.arange(len(audio)), audio).astype(np.float32)


def to_pcm(audio: np.ndarray) -> bytes:
    clipped = np.clip(audio, -32767, 32767).astype(np.int16)
    return clipped.tobytes()


def silence(seconds: float) -> np.ndarray:
    return np.zeros(int(SAMPLE_RATE * seconds), dtype=np.float32)


def filtered_noise(n: int, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    white = rng.normal(0, 1, n)
    out = np.empty(n, dtype=np.float64)
    acc = 0.0
    for i, sample in enumerate(white):
        acc = 0.94 * acc + 0.06 * sample
        out[i] = acc
    mixed = out + 0.2 * white
    mixed /= np.sqrt(np.mean(mixed**2) + 1e-9)
    return mixed.astype(np.float32)


def mix_snr(speech: np.ndarray, noise: np.ndarray, snr_db: float) -> np.ndarray:
    noise = noise[: len(speech)]
    speech_power = float(np.mean(speech**2) + 1e-9)
    noise_power = float(np.mean(noise**2) + 1e-9)
    target = speech_power / (10 ** (snr_db / 10))
    scaled = noise * np.sqrt(target / noise_power)
    mixed = speech + scaled
    peak = float(np.max(np.abs(mixed)))
    if peak > 28000:
        mixed *= 28000 / peak
    return mixed


def recognize(rec: KaldiRecognizer, audio: np.ndarray) -> str:
    rec.Reset()
    pcm = to_pcm(np.concatenate([audio, silence(1.2)]))
    step = SAMPLE_RATE * 2 // 5  # 0.2 s of 16-bit samples, in bytes
    heard = ""
    for start in range(0, len(pcm), step):
        chunk = pcm[start : start + step]
        if rec.AcceptWaveform(chunk):
            heard = json.loads(rec.Result()).get("text", "")
    final = json.loads(rec.FinalResult()).get("text", "")
    return final or heard


def hit(text: str) -> bool:
    return WAKE in text


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    cpu = "unknown"
    for line in Path("/proc/cpuinfo").read_text().splitlines():
        if line.startswith("model name"):
            cpu = line.split(":", 1)[1].strip()
            break
    print("loading model", flush=True)
    model = Model(str(MODEL))
    rec = KaldiRecognizer(model, SAMPLE_RATE, grammar())
    phrases = [
        "hey guide",
        "hey guide start",
        "hey guide stop",
        "hey guide what is in front of me",
        "hey guide repeat",
    ]
    rows = []
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        cache: dict[tuple[str, int], np.ndarray] = {}

        def speak(text: str, rate: int = 140) -> np.ndarray:
            key = (text, rate)
            if key not in cache:
                wav = tmp_path / f"{abs(hash(key))}.wav"
                synth(text, rate, "en-us", wav)
                spoken = read_wav(wav)
                # espeak is quiet. Scale to a normal voice level.
                peak = float(np.max(np.abs(spoken)) + 1e-6)
                cache[key] = spoken * (16000 / peak)
            return cache[key]

        fillers = [
            speak("the market is crowded today", 130),
            speak("please wait for the signal", 150),
            speak("a bus is coming down the road", 120),
        ]

        def babble(n: int, seed: int) -> np.ndarray:
            rng = np.random.default_rng(seed)
            out = np.zeros(n, dtype=np.float32)
            for voice in fillers:
                start = int(rng.integers(0, max(1, n // 3)))
                piece = voice[: n - start] * float(rng.uniform(0.3, 0.8))
                out[start : start + len(piece)] += piece
            out += filtered_noise(n, seed + 99) * 800
            return out

        def trial(kind: str, text: str, audio: np.ndarray, snr) -> None:
            heard = recognize(rec, audio)
            rows.append(
                {
                    "kind": kind,
                    "said": text,
                    "snr_db": snr,
                    "heard": heard,
                    "wake_hit": hit(heard),
                }
            )
            print(f"{kind} snr={snr} said={text!r} heard={heard!r}", flush=True)

        for rate in (130, 160):
            for phrase in phrases:
                trial("clean", phrase, speak(phrase, rate), None)

        for snr in (20, 15, 10, 5, 0):
            for seed in (1, 2):
                for phrase in phrases:
                    speech = speak(phrase, 140)
                    noise = babble(len(speech), seed + int(snr * 10))
                    trial("babble", phrase, mix_snr(speech, noise, snr), snr)

        unrelated = [
            "what time is it",
            "the weather is fine",
            "turn left at the shop",
            "hello there",
            "stop the music please",
            "start the car",
        ]
        for phrase in unrelated:
            trial("unrelated", phrase, speak(phrase, 140), None)
            speech = speak(phrase, 140)
            trial("unrelated_babble_10db", phrase, mix_snr(speech, babble(len(speech), 7), 10), 10)

        false_alarms = 0
        noise_clips = 0
        for seed in range(8):
            noise_clips += 1
            heard = recognize(rec, babble(SAMPLE_RATE * 6, 200 + seed))
            woke = hit(heard)
            false_alarms += int(woke)
            rows.append(
                {
                    "kind": "noise_only",
                    "said": "",
                    "snr_db": None,
                    "heard": heard,
                    "wake_hit": woke,
                }
            )
            print(f"noise_only heard={heard!r}", flush=True)

        # CPU: 30 seconds of audio, timed around recognition only.
        loop = speak("hey guide start", 140)
        gap = silence(0.8)
        chunk = np.concatenate([loop, gap, babble(SAMPLE_RATE, 3) * 0.2])
        repeats = int(np.ceil(SAMPLE_RATE * 30 / len(chunk)))
        long_audio = np.tile(chunk, repeats)[: SAMPLE_RATE * 30]
        rec.Reset()
        start_usage = resource.getrusage(resource.RUSAGE_SELF)
        wall0 = time.perf_counter()
        recognize(rec, long_audio)
        wall1 = time.perf_counter()
        end_usage = resource.getrusage(resource.RUSAGE_SELF)
        cpu_seconds = (end_usage.ru_utime + end_usage.ru_stime) - (
            start_usage.ru_utime + start_usage.ru_stime
        )
        audio_seconds = 30.0
        rtf = cpu_seconds / audio_seconds

    def rate(kind: str, snr=None) -> dict:
        chosen = [
            row
            for row in rows
            if row["kind"] == kind and (snr is None or row["snr_db"] == snr)
        ]
        hits = sum(1 for row in chosen if row["wake_hit"])
        return {"n": len(chosen), "hits": hits, "rate": (hits / len(chosen) if chosen else None)}

    summary = {
        "machine": cpu,
        "cores": os.cpu_count(),
        "model": "vosk-model-small-en-us-0.15",
        "license": "Apache-2.0",
        "wake_phrase": WAKE,
        "grammar": "wake phrase, wake plus English command, [unk]",
        "speech": "espeak-ng en-us, not a human recording",
        "noise": "synthetic babble of espeak phrases plus low-pass noise, not a street recording",
        "not_a_phone": True,
        "clean": rate("clean"),
        "babble_by_snr": {str(snr): rate("babble", snr) for snr in (20, 15, 10, 5, 0)},
        "unrelated_false_alarms": rate("unrelated"),
        "unrelated_in_babble_false_alarms": rate("unrelated_babble_10db"),
        "noise_only_false_alarms": {"n": noise_clips, "hits": false_alarms},
        "cpu_realtime_factor": rtf,
        "cpu_seconds_for_30s_audio": cpu_seconds,
        "wall_seconds_for_30s_audio": wall1 - wall0,
        "max_rss_kb": end_usage.ru_maxrss,
        "rows": rows,
    }
    OUT.write_text(json.dumps(summary, indent=2))
    print(
        json.dumps({k: summary[k] for k in summary if k != "rows"}, indent=2),
        flush=True,
    )


if __name__ == "__main__":
    main()
