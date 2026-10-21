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

## What each setting does

**Language.** Chooses the words that are spoken, and the words on the screen. English is included. Hindi, Tamil, Bengali, Swahili, and Spanish are marked as drafts. If that language has no offline voice on the phone, the app says so in English and speaks the warnings in English until a voice is installed.

**Speech rate.** Slow, Normal, or Fast. Normal is a little brisk, so a warning can finish while you are still walking.

**Battery saver.** When this is on, the camera is checked about every 1.5 seconds instead of about 3 times a second. Warnings come less often. The phone uses less battery. Use this for a long walk.

**Vibration.** When this is on, a very close object ahead makes the phone vibrate in three pulses, as well as speak. Turn it off if you do not want vibration.

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

- It cannot see every obstacle. Steps, curbs, holes, poles, hanging wires, and clear glass are often missed.
- Distance is only a rough guess from how big the object looks: very close, near, or far. It is not meters.
- It does not know if something is moving toward you.
- It only speaks some everyday objects the model knows (a person, a chair, a car, and others listed below). It stays quiet about many other detections so it does not talk all the time.
- A covered camera, a dark street, or a phone pointed at the sky will miss what is in front of you.
- Some phones stop the camera when the screen is off even after you change the battery setting. If that happens, keep the screen on, or use battery saver and a power bank.

## For developers

Package name: `org.visualaid.obstacleguide`. The app runs on Android 7.0 and newer (minSdk 24) and targets Android 15 (SDK 35). The manifest does not request the INTERNET permission.

Build tools: JDK 17 or newer, Android SDK platform 35, build-tools 35.0.0.

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew test assembleDebug
```

Unit tests cover side, distance, priority, the 4 second throttle, and the phrase files. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It contains ARM 32-bit and ARM 64-bit code only, so it installs on the phones named above and stays smaller. It will not install on an x86 emulator.

### How a warning is chosen

The rear camera feeds CameraX. Frames are turned upright and passed to TensorFlow Lite Task Vision. In battery saver the gap between frames is 1500 ms. Otherwise it is 300 ms.

Each box is normalized to the upright frame.

- Side: center x below 0.35 is left, above 0.65 is right, otherwise ahead.
- Very close: box area at least 0.22, or height at least 0.55, or the bottom edge is at least 0.92 and the height is at least 0.22.
- Near: area at least 0.07, or height at least 0.28, or the bottom edge is at least 0.75 and the height is at least 0.12.
- Far: anything else above the confidence cutoff of 0.5.
- Priority: closer beats more central. Inside the same distance, the more central box wins, then the larger box, then the box that sits lower in the frame.
- Only the top two are considered. The same object, side, and distance is not spoken again for 4 seconds. A very close object ahead may interrupt current speech and triggers the vibration pattern 280 ms on, 120 ms off, three times.
- Sentences are not built in code. Each language file has nine fixed patterns and separate object names. The pattern contains one placeholder for the object.

The screen can lock because the work runs in a foreground service of type `camera`, with a partial wake lock and a persistent notification.

### Model

File: `app/src/main/assets/efficientdet_lite0.tflite` (about 4.4 MB).

It is EfficientDet-Lite0, int8, input 320 by 320, with the built-in TFLite detection post-process and a COCO label map inside the file. The runtime is `org.tensorflow:tensorflow-lite-task-vision:0.4.4` (TensorFlow Lite Task library ObjectDetector, two CPU threads). No network call is made.

The label map uses `???` for unused COCO id slots. Those are ignored. The model can emit these class names:

person, bicycle, car, motorcycle, airplane, bus, train, truck, boat, traffic light, fire hydrant, stop sign, parking meter, bench, bird, cat, dog, horse, sheep, cow, elephant, bear, zebra, giraffe, backpack, umbrella, handbag, tie, suitcase, frisbee, skis, snowboard, sports ball, kite, baseball bat, baseball glove, skateboard, surfboard, tennis racket, bottle, wine glass, cup, fork, knife, spoon, bowl, banana, apple, sandwich, orange, broccoli, carrot, hot dog, pizza, donut, cake, chair, couch, potted plant, bed, dining table, toilet, tv, laptop, mouse, remote, keyboard, cell phone, microwave, oven, toaster, sink, refrigerator, book, clock, vase, scissors, teddy bear, hair drier, toothbrush.

Spoken classes, chosen to match the earlier web prototype and to limit chatter:

person, bicycle, car, motorcycle, bus, truck, traffic light, fire hydrant, stop sign, parking meter, bench, cat, dog, horse, sheep, cow, backpack, umbrella, suitcase, bottle, chair, couch, potted plant, bed, dining table, toilet, tv, oven, sink, refrigerator.

Spoken short names in English: plant for potted plant, table for dining table, television for tv. Other spoken names match the class name, including "fire hydrant" and "stop sign."

The model and the TensorFlow Lite task library are under the Apache License 2.0.

### Project map

- `logic/WarningLogic.kt` — side, distance, priority, throttle. No Android APIs, so unit tests run on a normal JVM.
- `logic/PhraseIds.kt` — resource names for every pattern and object.
- `speech/PhraseBook.kt` — loads the pattern and the object name from the chosen locale.
- `speech/GuideSpeaker.kt` — Android TextToSpeech, offline voices only.
- `detect/ObstacleDetector.kt` — loads the bundled model.
- `GuideService.kt` — foreground camera service.
- `MainActivity.kt` — the high-contrast screen, permission flow, and settings.
- `res/values-hi`, `values-ta`, `values-bn`, `values-sw`, `values-es` — draft translations.
