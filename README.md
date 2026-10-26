# RaahiSense

Raahi means traveller. RaahiSense speaks short warnings about things the rear camera sees, while you walk. It is an extra to a white cane. It is not a replacement. It does not need the internet. It does not save photos unless **Collect training photos** is on, and then the photos stay on that phone until you export them. The Android app and the iPhone app live in this same project. Prepared for Dr. Chindanoor Rajesh.

The Android package name is still `org.visualaid.obstacleguide`. The name on the phone is RaahiSense.

It is meant for a simple unlocked phone, such as a Samsung Galaxy A16 5G or a Moto G from 2025, including phones with about 4 GB of memory and no SIM card.

Hindi, Tamil, Bengali, Swahili, and Spanish phrases in this version are drafts. A native speaker must review them before you trust the wording. English is the source wording.

## Install the app

You need the file `ObstacleGuide-native.apk`. If someone built it on a computer, the same file is also at `app/build/outputs/apk/debug/app-debug.apk`.

1. Copy the APK onto the phone. A USB cable is enough. You can also use Bluetooth or a memory card. The phone does not need a SIM or the internet.
2. On the phone, open the Files app and tap the APK.
3. If Android asks you to allow installs, allow them for the Files app, then tap Install.
4. Open **RaahiSense**.

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
2. The phone asks to use the camera. Allow it. Photos are not saved unless Collect training photos is on.
3. The big button then says **START**. Tap it.
4. You should hear "Started. Listening for obstacles."
5. Lock the screen if you want. A notification stays up: **Obstacle Guide is on**. That means it is still working.
6. To close RaahiSense, say **stop** (or **stop app**, **exit**, or **close**), tap **Stop RaahiSense**, or open the notification and tap Stop. The phone says "RaahiSense stopped", then stops the camera, the warnings, speech, and the listening service, and removes the notification. On Android the app closes. On iPhone the work stops and the screen can sleep; iOS does not let an app remove itself. The big **STOP** button only turns warnings off and leaves the app open. **Pause** does that by voice, and **resume** turns warnings on again.

On Android you can also touch and hold the RaahiSense icon. TalkBack reads two shortcuts: **Start guidance** and **Stop RaahiSense**. Start guidance opens the app and starts warnings, the same as the big START button. Stop RaahiSense is the full stop above, not the big STOP button. Those long-press shortcuts work as soon as the app is installed.

"Hey Google, start RaahiSense", "Hey Google, stop RaahiSense", and "Hey Google, close RaahiSense" are Assistant App Actions. Google turns those voice phrases on fully only after the app is published on the Play Store. Until then, Hey Google may only open the app, or it may do nothing for stop. The long-press shortcuts still work on a sideloaded APK.

On iPhone, Siri Shortcuts **Start RaahiSense** and **Stop RaahiSense** are built in. You can say "Hey Siri, start RaahiSense", "Hey Siri, stop RaahiSense", or "Hey Siri, close RaahiSense". Stop is the same full stop as the Stop RaahiSense button. Open RaahiSense once after installing so Siri can learn the shortcuts. iOS still cannot remove the app from the screen.

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

- start, or resume
- stop, stop app, exit, or close (closes the app)
- pause (turns warnings off and leaves the app open)
- what is in front of me
- repeat
- louder, or quieter
- faster, or slower
- change language
- start collecting, or stop collecting (saves training photos, warnings stay on)
- missed (an obstacle was not warned)
- wrong, false alarm, or wrong warning (a warning was false or the wrong object)
- help

You can also say the wake phrase and the command in one breath, such as "Hey Guide, start."

While RaahiSense is speaking, the microphone is ignored, including a short moment after the phrase ends. Right after a warning, you can say **wrong** or **missed** during that moment, and the phone says "Noted." Other commands still wait until the phrase and that short moment have finished. A warning such as "stop sign" does not close the app. Say those commands after the phrase finishes.

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

**Light.** Auto, Always on, or Off. Auto turns the flashlight on when the scene stays dark for a few seconds, and says "It's dark, turning on the light." It turns the light off only after the scene has stayed brighter for a few seconds, so it does not flicker. If it is still too dark with the light, or the phone has no flashlight, it says "Too dark to see clearly, please use caution," then waits at least a minute before saying that again. Always on keeps the flashlight on and does not say the turning-on sentence. Off leaves the flashlight off and can still say the caution sentence. In low light the detector accepts a slightly lower score, and the camera exposure is raised by about one stop when the camera allows it. The same debounce still applies: three frames for a still depth warning, four frames for something approaching, and four seconds before the same warning repeats.

**Vibration.** When this is on, a very close object ahead makes the phone vibrate in three pulses, as well as speak. Turn it off if you do not want vibration.

**Voice commands.** On or off. On means the phone listens for "Hey Guide" even when the screen is locked, as long as the notification is showing. Off closes the microphone. The wake phrase box is next to this button.

**Collect training photos.** Off unless you turn it on. This is for the developer walking around to gather training pictures. Warnings keep running. About every 1.5 seconds the phone saves a full-resolution photo, and it also saves a short burst when a warning is spoken, when you say **missed**, or when you say **wrong**. Each photo has a JSON file next to it with the time, the detections, a depth summary, the kind of event (periodic, warning, missed, or wrong), and a lighting estimate. A wrong photo also records the last spoken warning: its label, box, confidence, and the time it was spoken. GPS is off unless you turn on **Save GPS with photos**. On Android the files are in the app's own external folder `RaahiSense/collect/`, one folder per date. The screen shows how many photos are saved and how much space they use. **Export training photos** zips the newest day and opens the share sheet, so you can send it to Google Drive yourself. The app does not upload anything. JPEG quality is about 90 percent. If the phone gets hot, saving pauses and warnings continue. Faces are not blurred in this personal collection. Blur them before sharing the zip with anyone else.

The interval button steps through 1 second, 1.5 seconds, and 3 seconds. Say **start collecting** or **stop collecting** after Hey Guide, or tap the button. **Stop collecting** does not close the app and does not turn warnings off.

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
- A covered camera, a street the flashlight cannot light, or a phone pointed at the sky will miss what is in front of you. The caution sentence is a warning to slow down, not a detection.
- Some phones stop the camera when the screen is off even after you change the battery setting. If that happens, keep the screen on, or use battery saver and a power bank.
- The wake phrase is English. A noisy street can hide it. The noise test above was synthetic speech, not a recorded street.
- Volume-key and double-tap commands work only while this app's screen is open. They do not work on the lock screen.
- A hard shake can be triggered by a drop or by shaking the phone in a bag. Voice commands off also turns the shake off.
- With TalkBack on, double-tap is left for TalkBack. Use the wake phrase, a shake, or the launcher shortcuts. Touch and hold the RaahiSense icon. TalkBack reads **Start guidance** and **Stop RaahiSense**.

## For developers

Package name: `org.visualaid.obstacleguide`. The app runs on Android 7.0 and newer (minSdk 24) and targets Android 15 (SDK 35). The manifest does not request the INTERNET permission.

Build tools: JDK 17 or newer, Android SDK platform 35, build-tools 35.0.0.

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew test assembleDebug
```

Unit tests cover side, distance, priority, the 4 second throttle, depth warnings (unknown object, wall, approaching, debounce), the flashlight decision, and the phrase files. The debug build is split by CPU. The Samsung test phone wants `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (77,826,468 bytes in the build that added the flashlight). A 32-bit ARM APK is written beside it. There is no x86 build, so it will not install on an x86 emulator. R8 and resource shrinking run on that debug APK, and debug symbols are stripped from the native libraries. The full Vosk model, the full depth model, and the detector stay inside the APK. Nothing is downloaded later. The depth model is not quantized further.

### How a warning is chosen

The rear camera feeds CameraX, unless ARCore is already installed and supports depth. In that case ARCore supplies the camera picture and a metric depth map, and the app does not download ARCore. If ARCore is missing, the monocular depth model runs beside the detector. In battery saver the gap between detector frames is 1500 ms, and depth runs about every 2 seconds. Otherwise the detector gap is 300 ms and depth runs about twice a second. The depth model input is fixed at 518 by 686, so it is not run on every frame.

Each box is normalized to the upright frame.

- Side: center x below 0.35 is left, above 0.65 is right, otherwise ahead.
- Very close: box area at least 0.22, or height at least 0.55, or the bottom edge is at least 0.92 and the height is at least 0.22.
- Near: area at least 0.07, or height at least 0.28, or the bottom edge is at least 0.75 and the height is at least 0.12.
- Far: anything else above the confidence cutoff of 0.5, or 0.4 when the scene is low light. The lower cutoff is also what lets a dim box explain a depth column, so an unknown-object warning is not spoken on top of it.
- Priority: closer beats more central. Inside the same distance, the more central box wins, then the larger box, then the box that sits lower in the frame.
- Only the top two are considered. The same object, side, and distance is not spoken again for 4 seconds. A very close object ahead may interrupt current speech and triggers the vibration pattern 280 ms on, 120 ms off, three times.
- Sentences are not built in code. Each language file has nine fixed patterns and separate object names. The pattern contains one placeholder for the object. Wall, unknown-object, and approaching warnings are whole sentences in each language file, because their word order is not the object pattern.
- Depth looks at the lower middle of the frame (about 42% to 88% of the height) and the same left / ahead / right columns. A larger depth value means farther. A detector box that covers a column suppresses an unknown object or a wall there. A wall is spoken only when the close region is large, flat, and reaches from about 22% to 78% of the width, for 3 frames in a row. Any other close, unexplained column becomes an unknown object after those same 3 frames. Approaching needs 4 frames, each step still shrinking, and the newest value at most 82% of the oldest, and the region must already be near or very close. Its priority is above any still warning. One close frame, or a value that bounces up and down, does not speak.
- Brightness uses the mean luma of the frame. When the camera provides ISO or exposure time, a dim frame with ISO at least 800 or an exposure of at least 40 ms also counts as dark. An ambient light sensor below 8 lux counts as dark unless the frame itself is clearly bright. Auto turns the flashlight on after 2.5 seconds of darkness and off after 3 seconds of brightness. The too-dark caution repeats no sooner than every 60 seconds. ARCore holds the camera, so the flashlight is treated as unavailable on that path and the caution sentence is used instead.

The screen can lock because the work runs in a foreground service. The service type is `camera` while warnings are on, and `microphone` while voice commands are on. A partial wake lock and a persistent notification stay up for either one. When a command is being captured, new warning speech is held back. The latest boxes are still kept, so "what is in front of me" can speak them. Passive wake-word listening does not mute warnings.

### Model

Detector file: `app/src/main/assets/obstacles_v1.tflite` (3,050,625 bytes). It is a YOLO11n head, input 320, int8 weights, run by `org.tensorflow:tensorflow-lite` (two CPU threads) and decoded in `detect/YoloDecode.kt`. No network call is made. On this Intel Xeon, Python TFLite with two threads took about 3.3 ms per invoke. That is not a Helio G99 measurement.

Held-out scores after 4 epochs on this CPU (the trainer reported 3.23 hours). Validation mAP50 0.571, mAP50-95 0.400. Test mAP50 0.559, mAP50-95 0.395. Indoor test mAP50-95: person 0.38, dog 0.40, cat 0.51, chair 0.14, couch 0.27, dining table 0.25, bed 0.42, toilet 0.55, potted plant 0.15, refrigerator 0.43, tv 0.45, door 0.18, stairs 0.42. Curb validation mAP50-95 is 0.111 (274 train images, 61 val). The test split has no curb pictures, so there is no curb test score. Potholes are also weak (test mAP50-95 0.08). These are short CPU runs, not a phone benchmark. Full per-class numbers are in `training/metrics/train_report.json`.

The same test pictures were darkened (brightness, gamma, noise, and motion blur) and scored again on this CPU. Before any low-light fine-tune, that darkened split scored mAP50 0.283 and mAP50-95 0.189. One further epoch with those same effects on training batches scored mAP50 0.292 and mAP50-95 0.185 on the darkened split, and the normal validation mAP50-95 fell from 0.400 to 0.261. That epoch was not packaged. ExDark, BDD100K, and nuScenes were not added: their terms are research or non-commercial. A Roboflow night-vision project lists CC BY 4.0, but the photos are a reupload without a confirmed source license, so those pictures were not added either. Numbers are in `training/metrics/lowlight_report.json`.

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
- `AssistantActionActivity.kt` — exported deep link `raahisense://start`, `raahisense://stop`, and `raahisense://close`. It only accepts those hosts. Stop calls `GuideService.ACTION_QUIT`.
- `res/xml/shortcuts.xml` — launcher shortcuts **Start guidance** and **Stop RaahiSense**, plus Assistant capabilities `actions.intent.OPEN_APP_FEATURE` and custom start, stop, and close intents. Launcher shortcuts work immediately. Assistant voice phrases work fully only after Play Store publishing.
- `AboutActivity.kt` — speech and picture credits.
- `res/values-hi`, `values-ta`, `values-bn`, `values-sw`, `values-es` — draft translations.

## iPhone app

The iPhone app is in `ios/`. It is native Swift and SwiftUI. It uses the same obstacle model, the same depth model, and the same spoken phrases as Android. The warning rules (side, distance, wall, unknown object, approaching, throttle, low light, shake) live in `ios/RaahiSenseLogic` and are covered by unit tests. The models are not converted to Core ML and they are not shrunk. A build copies `obstacles_v1.tflite`, `depth_anything_v2_small.tflite`, and the English wake-word word list out of `app/src/main/assets`. The Vosk zip is not copied. iOS does not have a public Vosk library.

There is no Mac in the environment that built this repository, and this repository's remote is not GitHub, so the iPhone app has not been compiled here. The check that compiles it is `.github/workflows/ios.yml`. After this project is on GitHub, that workflow runs on a free GitHub-hosted macOS runner. It does not need an Apple Developer account. It turns signing off, generates the Xcode project with XcodeGen, runs `swift test` on the shared logic, builds RaahiSense, and runs the same logic tests on an iPhone simulator.

### What the iPhone app does

- Rear camera, portrait, so a shirt pocket with the camera facing the way you walk can work while the screen is on.
- Spoken warnings with distance, including "pothole" and the other 29 classes, plus wall, unknown object, and approaching.
- The screen stays awake while guiding. A very close object ahead vibrates in three pulses (about 280 ms on, 120 ms off) when vibration is on.
- Hey Guide, then one command. A large Listen button does the same job without the wake phrase. A hard shake, or a double-tap on the screen when VoiceOver is off, also opens that command window.
- English is the default. Hindi, Tamil, Bengali, Swahili, and Spanish are the same drafts as Android. The wake ear is English on both phones.
- VoiceOver: the big button, Listen, and each setting have labels. Warnings are spoken by the speech synthesizer and are not announced a second time by VoiceOver.
- Siri Shortcuts **Start RaahiSense** and **Stop RaahiSense** (also "close"). Stop uses the same full stop as the Stop RaahiSense button. The phrases are App Intents in `ios/RaahiSense/GuideShortcuts.swift`.
- Black and yellow, one large Start/Stop button.
- Auto flashlight from the picture brightness, plus ISO and exposure time when the camera provides them. There is no ambient light sensor API on iOS.
- Distance calibration, battery saver, speech rate, speech volume, and vibration match the Android settings.

### What iOS will not do

These are platform limits, not missing pieces of the Android design.

- Locking the screen, or leaving RaahiSense, stops the camera. Android can keep the camera running in a foreground service with the screen locked. iOS does not allow that for a normal app. The screen is kept on while guiding so a pocket with the screen awake can keep working. A warning that has already started can finish in the background because the app asks for the audio background mode. That mode does not keep the camera on. The app does not play a silent sound to stay alive.
- Holding a volume key does not start listening. iOS does not give that key to the app. Use Hey Guide, Listen, a double-tap when VoiceOver is off, or a hard shake. The shake also stops when iOS suspends the app.
- Hey Guide uses Apple's on-device English speech recognizer (`requiresOnDeviceRecognition`). It is not the Vosk model. If that recognizer is not downloaded, the phone says so. Warnings still work from the buttons. Voice is not sent to a server. There is no API key.
- The iPhone has no public light sensor for apps. Darkness uses the camera picture, ISO, and exposure time.
- ARCore does not exist on iOS. Depth is the same monocular model as Android.
- This copy has not been installed on an iPhone. The simulator build does not prove the camera, the torch, or the wake word on a real phone.

### Put it on TestFlight

The only paid Apple requirement is the Apple Developer Program, 99 US dollars a year. The account to use is `raahisenseapp@gmail.com`. It is not enrolled yet. Do not buy a Mac, a second Apple service, or a paid build host for this step. GitHub-hosted macOS runners are enough. A public GitHub repository can run them without a paid GitHub plan. A private repository spends the included Actions minutes, and macOS minutes are counted at a higher rate.

The upload workflow is `.github/workflows/ios-testflight.yml`. It does not run on a normal push. It runs only when you start it by hand, and it stops immediately if the signing secrets are missing.

1. Enroll `raahisenseapp@gmail.com` in the Apple Developer Program.
2. Put this project on GitHub if it is not there yet. The simulator workflow and the TestFlight workflow both run on GitHub, not on the machine that stores the code today.
3. In App Store Connect, create an app named RaahiSense. The bundle id is `app.raahisense.guide`. Leave encryption set to exempt. The app declares `ITSAppUsesNonExemptEncryption` as false. It does not use the network.
4. In App Store Connect, open Users and Access, then Integrations, then App Store Connect API. Create a key that can upload builds. Download the `.p8` file once. Write down the Key ID, the Issuer ID, and the 10-character Team ID from the membership page.
5. In the GitHub repository, open Settings, then Secrets and variables, then Actions. Add these secrets. Paste the whole `.p8` file, including the BEGIN and END lines, into the last one.
   - `APP_STORE_CONNECT_API_KEY_ID`
   - `APP_STORE_CONNECT_ISSUER_ID`
   - `APP_STORE_CONNECT_API_KEY_P8`
   - `TEAM_ID`
6. Open Actions, choose iOS TestFlight, and run the workflow. The archive is signed automatically with that key. Each run uses the GitHub run number as the build number.
7. When the build appears under TestFlight, add yourself as an internal tester. On the iPhone, install Apple's TestFlight app and accept the build. The iPhone signs in with an Apple ID. Internal testers must be users on the App Store Connect team. A wider beta needs Beta App Review, which is still inside the 99 dollar program.

`ios/signing/ExportOptions.plist` is the export template. The workflow fills in `TEAM_ID` when it runs. You do not need a Mac to do these steps.

### iOS project map

- `ios/project.yml` — XcodeGen project. The `.xcodeproj` is generated and is not committed.
- `ios/RaahiSenseLogic` — shared warning, depth, low-light, shake, and voice-command logic.
- `ios/RaahiSense` — camera, TensorFlow Lite, speech, haptics, wake word, and the VoiceOver screen.
- `ios/scripts/sync_strings.py` — copies Android `strings.xml` into `Localizable.strings`. `%1$s` becomes `%@`.
- `.github/workflows/ios.yml` — simulator compile and tests, signing off.
- `.github/workflows/ios-testflight.yml` — manual TestFlight upload, after the secrets exist.
