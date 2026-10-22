# Obstacle Guide

Obstacle Guide speaks short warnings about things the rear camera sees, while you walk. It is an extra to a white cane. It is not a replacement. It does not need the internet. It does not save photos.

It is meant for a simple unlocked phone, such as a Samsung Galaxy A16 5G or a Moto G from 2025, including phones with about 4 GB of memory and no SIM card.

Hindi, Tamil, Bengali, Swahili, and Spanish phrases in this version are drafts. A native speaker must review them before you trust the wording. English is the source wording.

## Install the app

You need the file `ObstacleGuide-native.apk`. If someone built it on a computer, the same file is also at `app/build/outputs/apk/debug/app-debug.apk`.

1. Copy the APK onto the phone. A USB cable is enough. You can also use Bluetooth or a memory card. The phone does not need a SIM or the internet.
2. On the phone, open the Files app and tap the APK.
3. If Android asks you to allow installs, allow them for the Files app, then tap Install.
4. Open **Obstacle Guide**.

On older phones the switch is under Settings, Security, Unknown sources. On newer Samsung and Moto phones it is under Settings, Security, or Apps, then Special access, then Install unknown apps.

## Before you walk

- Charge the phone.
- Practice indoors, with another person watching.
- Hold the phone upright, with the back camera facing the way you walk. A shirt pocket that faces forward can work if the camera is not covered. The screen can be locked after you tap Start.
- In a noisy street, wired headphones are easier to hear. The phone speaker also works.
- This app misses steps, holes, poles, wires, glass, and many other dangers. Keep your cane in front of you.
- Do not test it alone near traffic.

## How to use it

The screen is black and yellow, with one big button.

1. Tap the big button.
2. The phone asks to use the camera. Allow it. Photos are not saved.
3. The big button then says **START**. Tap it.
4. You should hear "Started. Listening for obstacles."
5. Lock the screen if you want. A notification stays up: **Obstacle Guide is on**. That means it is still working.
6. To stop, open the app and tap the big **STOP** button, or open the notification and tap Stop.

Warnings sound like "Very close chair ahead" or "Near car on the left." The same warning is not repeated for about 4 seconds. A very close object straight ahead can interrupt other speech, and the phone vibrates in three pulses if vibration is on.

If something close has no name the detector knows, you hear "Unknown object ahead, very close," or the same phrase on the left or the right, near or very close. You hear "Wall ahead" only when a close, flat surface spans most of the picture. If a region gets closer across several looks, you hear "Something approaching on your left" (or ahead, or on the right). That warning is spoken before a warning about something that is standing still. A one-frame shadow or a camera shake does not speak.

**Calibrate distance** is on the main screen. Start the guide first. Tap it, point along an open path, and tap again. Then point at a wall one step away, and tap again. The phone remembers those two readings. Do this once on each phone. Without it, a wall that fills the whole picture can be missed, because the picture alone cannot tell a near wall from open air.

Indoors, the same warnings cover a person, a cat, a dog, a chair, a couch, a table, a bed, a toilet, a plant, a refrigerator, a television, a door, and stairs. Outdoors it also speaks about cars, cycles, buses, trucks, cows, auto-rickshaws, cycle rickshaws, handcarts, potholes, open drains, curbs, poles, barriers, street objects, and trees. It still misses a lot. Keep the cane outside, and go slowly in an unfamiliar room.

## Voice commands

The main way to give a command is a wake phrase, because a small button is hard to find by touch. The default phrase is **Hey Guide**. You can change it in the box under the voice button. The new phrase has to be words the offline English ear already knows. If it does not know a word, the phone says so and keeps the old phrase.

Voice commands are on the first time you open the app. The phone asks to use the microphone, and it speaks why before the system question. Sound is not sent off the phone. There is no account and no API key.

1. Say **Hey Guide**.
2. The warnings duck, and you hear "Yes?"
3. Say one command.
4. The phone confirms it out loud, then goes back to waiting for **Hey Guide**. Warnings continue while it is only waiting.

Commands, in English:

- start
- stop, or pause
- what is in front of me
- repeat
- louder, or quieter
- faster, or slower
- change language
- help

You can also say the wake phrase and the command in one breath, such as "Hey Guide, start."

Three backups need no aiming. They work only while voice commands are on:

- Hold a volume key while the Obstacle Guide screen is open. A short press still changes the volume.
- Double-tap anywhere on that screen. This is turned off while TalkBack is on, so TalkBack's own double-tap still activates buttons.
- Shake the phone hard, twice, in opposite directions. A normal walking sway does not count. The shake works with the screen off, because it runs in the same service as the wake word.

The volume key and the double-tap do not work while the screen is off. The wake phrase and the shake do, after you have allowed the microphone and the notification is up. If you only want the camera and not the microphone, tap **Voice commands: on** so it changes to off. The notification can also say **Turn voice off** when the camera is not running.

The ear is English. Hindi, Tamil, Bengali, Swahili, and Spanish screens still list command words for a later review, but this model hears the English words. Help says that. Warning speech can still be in the language you picked.

## What the wake word costs

This was measured on the build machine, an Intel Xeon with 4 cores, using the same Vosk model that is packed in the app (`vosk-model-small-en-us-0.15`, Apache License 2.0). It is not a measurement on a Galaxy A16 or a Helio G99, and it is not a battery-meter test, so there is no milliamp-hour number.

The recognizer, with the grammar limited to the wake phrase and the commands, used **0.61 seconds of CPU for 30 seconds of audio**. That is a realtime factor of **0.020**, about 2 percent of one core on this Xeon. The process peaked at about **188 MB** of memory. While the voice setting is off, the microphone is not opened, so this cost is not added.

A phone CPU is slower than this Xeon, and the phone also keeps a partial wake lock and the microphone hardware on. Those extra costs were not measured. Treat 2 percent of one server core as a floor for the recognizer alone, not as the phone's battery use.

The wake check used espeak-ng, not a person, mixed with synthetic babble (other synthetic voices plus rumble). It is not a real street recording.

| Condition | Wake phrase found |
| --- | --- |
| Clean speech, 10 clips | 10 of 10 |
| Babble at 20 dB and 15 dB, 10 clips each | 10 of 10 |
| Babble at 10 dB and 5 dB, 10 clips each | 9 of 10 |
| Babble at 0 dB, 10 clips | 7 of 10 |
| Other sentences, no wake phrase, 6 clean and 6 in babble | 0 false wakes |
| Noise only, 8 clips of 6 seconds | 0 false wakes |

At equal speech and noise (0 dB) it started to miss. In louder noise, say the phrase closer to the phone.

## What each setting does

**Language.** Chooses the words that are spoken, and the words on the screen. English is included. Hindi, Tamil, Bengali, Swahili, and Spanish are marked as drafts. If that language has no offline voice on the phone, the app says so in English and speaks the warnings in English until a voice is installed.

**Speech rate.** Slow, Normal, or Fast. Normal is a little brisk, so a warning can finish while you are still walking.

**Battery saver.** When this is on, the camera is checked about every 1.5 seconds instead of about 3 times a second. Warnings come less often. The phone uses less battery. Use this for a long walk.

**Vibration.** When this is on, a very close object ahead makes the phone vibrate in three pulses, as well as speak. Turn it off if you do not want vibration.

**Voice commands.** On or off. On means the phone listens for "Hey Guide" even when the screen is locked, as long as the notification is showing. Off closes the microphone. The wake phrase box is next to this button.

**Calibrate distance.** Two taps while the guide is running: an open path, then a wall one step away. This teaches the phone what "close" looks like on this camera.

**About and licenses** opens the credit for the speech model, the depth model, and the pictures.

**Keep running with the screen off.** Opens the phone's battery screen. Choose unrestricted, or "Do not optimize," so the phone is less likely to stop the camera when the screen locks. On Samsung phones you may also need Settings, Battery, Background usage limits, and then add Obstacle Guide to Never sleeping apps. Test this indoors: tap Start, lock the screen, and ask a helper to walk a chair into the camera view. You should still hear a warning.

## If the voice is missing

Many phones already have an English voice. Hindi, Tamil, Bengali, Swahili, or Spanish may need a voice download, and that download needs the internet once.

1. Open the phone Settings.
2. Search for "Text-to-speech" (on Samsung it may be under General management, or Accessibility).
3. Open the gear or settings next to the speech engine.
4. Install the offline voice for your language.
5. Come back to Obstacle Guide and choose that language again.

Until the voice is installed, the app tells you in spoken English, and it keeps speaking warnings in English. The message also stays on the screen.

## Test safely

- First test in a hallway or a room you know.
- Ask a helper to place a chair in front of the phone, then to the left, then to the right.
- You should hear a different side for left and right, and "ahead" when the chair is in the middle.
- Move the chair very close. You should hear "very close," and feel vibration if that is turned on.
- Lock the screen for a minute and repeat one test. If speech stops, use the battery setting above.
- Only after indoor tests work should anyone try a quiet outdoor path, still with a cane and a helper.

## What it cannot do

- It cannot see every obstacle. Glass doors and glass walls are often missed, because the camera can see through them or only see a reflection. Wires, wet floors, and low steps are often missed too. Curbs have fewer training pictures than cars, so a curb is easier to miss than a person.
- Distance is a rough grade: very close, near, or far. The depth model is relative, not meters. Calibration stores two readings from this phone. It does not turn the picture into a tape measure.
- Something moving toward you is warned only when its depth shrinks across several frames and it is already near. A slow drift, or the whole street shifting because you are walking an open path, is not called approaching.
- It names the objects the model knows (a person, a chair, a car, and others listed below). Anything close that those classes do not explain is an unknown object, or a wall when the close region is large, flat, and wide.
- A covered camera, a dark street, or a phone pointed at the sky will miss what is in front of you.
- Some phones stop the camera when the screen is off even after you change the battery setting. If that happens, keep the screen on, or use battery saver and a power bank.
- The wake phrase is English. A noisy street can hide it. The noise test above was synthetic speech, not a recorded street.
- Volume-key and double-tap commands work only while this app's screen is open. They do not work on the lock screen.
- A hard shake can be triggered by a drop or by shaking the phone in a bag. Voice commands off also turns the shake off.
- With TalkBack on, double-tap is left for TalkBack. Use the wake phrase or a shake instead.

## For developers

Package name: `org.visualaid.obstacleguide`. The app runs on Android 7.0 and newer (minSdk 24) and targets Android 15 (SDK 35). The manifest does not request the INTERNET permission.

Build tools: JDK 17 or newer, Android SDK platform 35, build-tools 35.0.0.

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew test assembleDebug
```

Unit tests cover side, distance, priority, the 4 second throttle, depth warnings (unknown object, wall, approaching, debounce), and the phrase files. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It contains ARM 32-bit and ARM 64-bit code only, so it installs on the phones named above and stays smaller. It will not install on an x86 emulator.

### How a warning is chosen

The rear camera feeds CameraX, unless ARCore is already installed and supports depth. In that case ARCore supplies the camera picture and a metric depth map, and the app does not download ARCore. If ARCore is missing, the monocular depth model runs beside the detector. In battery saver the gap between detector frames is 1500 ms, and depth runs about every 2 seconds. Otherwise the detector gap is 300 ms and depth runs about twice a second. The depth model input is fixed at 518 by 686, so it is not run on every frame.

Each box is normalized to the upright frame.

- Side: center x below 0.35 is left, above 0.65 is right, otherwise ahead.
- Very close: box area at least 0.22, or height at least 0.55, or the bottom edge is at least 0.92 and the height is at least 0.22.
- Near: area at least 0.07, or height at least 0.28, or the bottom edge is at least 0.75 and the height is at least 0.12.
- Far: anything else above the confidence cutoff of 0.5.
- Priority: closer beats more central. Inside the same distance, the more central box wins, then the larger box, then the box that sits lower in the frame.
- Only the top two are considered. The same object, side, and distance is not spoken again for 4 seconds. A very close object ahead may interrupt current speech and triggers the vibration pattern 280 ms on, 120 ms off, three times.
- Sentences are not built in code. Each language file has nine fixed patterns and separate object names. The pattern contains one placeholder for the object. Wall, unknown-object, and approaching warnings are whole sentences in each language file, because their word order is not the object pattern.
- Depth looks at the lower middle of the frame (about 42% to 88% of the height) and the same left / ahead / right columns. A larger depth value means farther. A detector box that covers a column suppresses an unknown object or a wall there. A wall is spoken only when the close region is large, flat, and reaches from about 22% to 78% of the width, for 3 frames in a row. Any other close, unexplained column becomes an unknown object after those same 3 frames. Approaching needs 4 frames, each step still shrinking, and the newest value at most 82% of the oldest, and the region must already be near or very close. Its priority is above any still warning. One close frame, or a value that bounces up and down, does not speak.

The screen can lock because the work runs in a foreground service. The service type is `camera` while warnings are on, and `microphone` while voice commands are on. A partial wake lock and a persistent notification stay up for either one. When a command is being captured, new warning speech is held back. The latest boxes are still kept, so "what is in front of me" can speak them. Passive wake-word listening does not mute warnings.

### Model

Detector file: `app/src/main/assets/obstacles_v1.tflite` (3,050,625 bytes). It is a YOLO11n head, input 320, int8 weights, run by `org.tensorflow:tensorflow-lite` (two CPU threads) and decoded in `detect/YoloDecode.kt`. No network call is made. On this Intel Xeon, Python TFLite with two threads took about 3.3 ms per invoke. That is not a Helio G99 measurement.

Held-out scores after 4 epochs on this CPU (the trainer reported 3.23 hours). Validation mAP50 0.571, mAP50-95 0.400. Test mAP50 0.559, mAP50-95 0.395. Indoor test mAP50-95: person 0.38, dog 0.40, cat 0.51, chair 0.14, couch 0.27, dining table 0.25, bed 0.42, toilet 0.55, potted plant 0.15, refrigerator 0.43, tv 0.45, door 0.18, stairs 0.42. Curb validation mAP50-95 is 0.111 (274 train images, 61 val). The test split has no curb pictures, so there is no curb test score. Potholes are also weak (test mAP50-95 0.08). These are short CPU runs, not a phone benchmark. Full per-class numbers are in `training/metrics/train_report.json`.

The 29 class names, in model order, are:

person, bicycle, motorcycle, car, bus, truck, cow, dog, auto_rickshaw, cycle_rickshaw, handcart, pothole, manhole_open_drain, curb, stairs, pole_bollard, barrier, street_furniture, tree, cat, chair, couch, dining table, bed, toilet, potted plant, refrigerator, tv, door.

Spoken short names in English include plant, table, television, door, refrigerator, auto rickshaw, and open drain. `speed_bump` and `low_branch` are not classes. Curbs have few pictures. Training and the held-out scores are in `training/train_obstacles.py` and `training/metrics/train_report.json`.

Depth file: `app/src/main/assets/depth_anything_v2_small.tflite` (27,733,680 bytes). It is Depth Anything V2 Small, Apache License 2.0, dynamic int8 weights and a float32 interface. Input is `[1, 3, 518, 686]` NCHW, ImageNet mean and standard deviation, stretched to that size. The raw output is disparity (a larger value is closer). The app negates it before the warning logic, which treats a larger value as farther. Base, large, and giant Depth Anything weights are not used. Their license is not Apache-2.0. On this same Xeon, two threads, one depth invoke took about 290 ms and the process was near 1 GB resident. That is about three frames a second if depth ran alone. The app asks for about two frames a second so the detector can keep going. No GPU delegate was available here. No phone and no milliamp-hour figure was measured. A Helio G99 will be slower than this Xeon. Numbers are in `training/metrics/depth_bench.json`.

`com.google.ar:core` is optional. When the device already has ARCore and depth mode, that metric map replaces the monocular model and shares the camera with the detector. The manifest marks ARCore optional and does not request INTERNET, so the app will not download it.

### Wake word

`com.alphacephei:vosk-android:0.3.75` loads `vosk-model-small-en-us-0.15` from assets. The zip is unpacked into app storage on first use. The recognizer grammar is the wake phrase, that phrase plus each in-vocabulary command, and `[unk]`. After a wake with no command attached, the grammar switches to the command list for 5 seconds, then returns to wake spotting. The same `AudioRecord` stays open across the switch. Sample rate 16000, source `VOICE_RECOGNITION`.

Command phrases live in `logic/VoiceCommands.kt` and in `cmd_*` strings. English phrases are always merged in. A translated phrase is put in the grammar only when every word is in `vosk-small-en-words.txt`. Parsing is whole-word, longest phrase wins, and does not need Android, so `CommandParserTest` runs on a normal JVM.

`ShakeWatch` fires on two opposite jerks within 700 ms, each at least 28 m/s², with a release under 14 m/s² between them, then a 3 second cooldown. Walking-sized motion is ignored. `ShakeWatchTest` covers that.

`training/bench_wake.py` writes `training/metrics/wake_bench.json`.

### Project map

- `logic/WarningLogic.kt` — side, distance, priority, throttle. No Android APIs, so unit tests run on a normal JVM.
- `logic/DepthWarnings.kt` — unknown object, wall, and approaching decisions. Covered by `DepthWarningsTest`.
- `logic/PhraseIds.kt` — resource names for every pattern, object, and depth sentence.
- `speech/PhraseBook.kt` — loads the pattern and the object name, or a whole depth sentence, from the chosen locale.
- `speech/GuideSpeaker.kt` — Android TextToSpeech, offline voices only.
- `detect/ObstacleDetector.kt` — loads the bundled detector.
- `detect/TfliteDepth.kt` — loads the monocular depth model.
- `detect/ArcoreDepthCamera.kt` — optional metric depth when ARCore is already installed.
- `detect/YoloDecode.kt` — box decode for the 29-class head, covered by `YoloDecodeTest`.
- `voice/VoskEar.kt` — wake word, then one command.
- `logic/VoiceCommands.kt` — localizable command matching.
- `logic/ShakeWatch.kt` — backup shake.
- `GuideService.kt` — foreground camera and microphone service.
- `MainActivity.kt` — the high-contrast screen, permission flow, settings, volume-key long-press, and double-tap.
- `AboutActivity.kt` — speech and picture credits.
- `res/values-hi`, `values-ta`, `values-bn`, `values-sw`, `values-es` — draft translations.
