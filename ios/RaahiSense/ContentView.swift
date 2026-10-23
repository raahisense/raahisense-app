import AVFoundation
import RaahiSenseLogic
import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var session: GuideSession
    @State private var wakeDraft = GuideSettingsWake.defaultPhrase
    @State private var voiceOver = UIAccessibility.isVoiceOverRunning

    var body: some View {
        Group {
            if session.showAbout {
                AboutView()
            } else {
                guide
            }
        }
        .background(GuideColor.background.ignoresSafeArea())
        .onAppear {
            session.prepare()
            wakeDraft = session.settings.wakePhrase
        }
        .onChange(of: session.settings.wakePhrase, perform: { phrase in
            wakeDraft = phrase
        })
        .onReceive(NotificationCenter.default.publisher(for: UIAccessibility.voiceOverStatusDidChangeNotification)) { _ in
            voiceOver = UIAccessibility.isVoiceOverRunning
        }
    }

    private var guide: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(phrase("app_name"))
                .font(.system(size: 34, weight: .bold))
                .foregroundColor(GuideColor.yellow)
                .accessibilityAddTraits(.isHeader)
            Text(phrase("tagline"))
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(GuideColor.yellow)
                .fixedSize(horizontal: false, vertical: true)
            bigButton
            listenButton
            Text(session.statusText)
                .font(.system(size: 22, weight: .semibold))
                .foregroundColor(GuideColor.yellow)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityLabel(session.statusText)
            Text(phrase("ios_screen_stays_on"))
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(GuideColor.yellow)
                .fixedSize(horizontal: false, vertical: true)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    settings
                }
                .padding(.bottom, 24)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .modifier(DoubleTapListen(enabled: !voiceOver && session.settings.voiceEnabled) {
            session.listenNow()
        })
    }

    private var bigButton: some View {
        Button(action: session.bigButton) {
            Text(bigTitle)
                .font(.system(size: 44, weight: .black))
                .foregroundColor(.black)
                .frame(maxWidth: .infinity, minHeight: 140)
                .background(GuideColor.yellow)
                .cornerRadius(16)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(bigAccessLabel)
    }

    private var listenButton: some View {
        Button(action: session.listenNow) {
            Text(phrase("listen_button"))
                .font(.system(size: 32, weight: .bold))
                .foregroundColor(.black)
                .frame(maxWidth: .infinity, minHeight: 88)
                .background(session.capturing ? Color.white : GuideColor.yellow)
                .cornerRadius(16)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(phrase("cd_listen"))
        .accessibilityAddTraits(session.capturing ? .isSelected : [])
    }

    private var settings: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(phrase("settings_heading"))
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(GuideColor.yellow)
                .accessibilityAddTraits(.isHeader)
            choice(phrase("label_language"))
            ForEach(GuideSettings.languageTags, id: \.self) { tag in
                option(
                    title: phrase("lang_\(tag)"),
                    selected: session.settings.languageTag == tag,
                    label: phrase("lang_\(tag)")
                ) { session.setLanguage(tag) }
            }
            choice(phrase("label_speech_rate"))
            option(title: phrase("rate_slow"), selected: session.settings.speechRate == GuideSettings.rateSlow, label: phrase("rate_slow")) {
                session.setRate(GuideSettings.rateSlow)
            }
            option(title: phrase("rate_normal"), selected: session.settings.speechRate == GuideSettings.rateNormal, label: phrase("rate_normal")) {
                session.setRate(GuideSettings.rateNormal)
            }
            option(title: phrase("rate_fast"), selected: session.settings.speechRate == GuideSettings.rateFast, label: phrase("rate_fast")) {
                session.setRate(GuideSettings.rateFast)
            }
            choice(phrase("label_volume"))
            option(title: phrase("volume_quiet"), selected: session.settings.speechVolume == GuideSettings.volumeLow, label: phrase("volume_quiet")) {
                session.setVolume(GuideSettings.volumeLow)
            }
            option(title: phrase("volume_medium"), selected: session.settings.speechVolume == GuideSettings.volumeMid, label: phrase("volume_medium")) {
                session.setVolume(GuideSettings.volumeMid)
            }
            option(title: phrase("volume_loud"), selected: session.settings.speechVolume == GuideSettings.volumeHigh, label: phrase("volume_loud")) {
                session.setVolume(GuideSettings.volumeHigh)
            }
            option(
                title: session.settings.batterySaver ? phrase("battery_on") : phrase("battery_off"),
                selected: session.settings.batterySaver,
                label: session.settings.batterySaver ? phrase("cd_battery_turn_off") : phrase("cd_battery_turn_on")
            ) { session.toggleBattery() }
            option(
                title: session.settings.vibrationEnabled ? phrase("vibration_on") : phrase("vibration_off"),
                selected: session.settings.vibrationEnabled,
                label: session.settings.vibrationEnabled ? phrase("cd_vibration_turn_off") : phrase("cd_vibration_turn_on")
            ) { session.toggleVibration() }
            option(
                title: session.settings.voiceEnabled ? phrase("wake_on") : phrase("wake_off"),
                selected: session.settings.voiceEnabled,
                label: session.settings.voiceEnabled ? phrase("wake_on") : phrase("wake_off")
            ) { session.toggleVoice() }
            Text(phrase("wake_phrase_label"))
                .font(.system(size: 20, weight: .bold))
                .foregroundColor(GuideColor.yellow)
            TextField(phrase("wake_phrase_hint"), text: $wakeDraft)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .font(.system(size: 22, weight: .semibold))
                .foregroundColor(.black)
                .padding(14)
                .background(GuideColor.yellow)
                .cornerRadius(10)
                .accessibilityLabel(phrase("wake_phrase_label"))
                .onSubmit { session.commitWake(wakeDraft) }
            option(title: phrase("save_wake_phrase"), selected: false, label: phrase("save_wake_phrase")) {
                session.commitWake(wakeDraft)
            }
            choice(phrase("label_light"))
            option(title: phrase("light_auto"), selected: session.settings.torchMode == .auto, label: phrase("light_auto")) {
                session.setTorch(.auto)
            }
            option(title: phrase("light_on"), selected: session.settings.torchMode == .on, label: phrase("light_on")) {
                session.setTorch(.on)
            }
            option(title: phrase("light_off"), selected: session.settings.torchMode == .off, label: phrase("light_off")) {
                session.setTorch(.off)
            }
            option(title: phrase("calibrate_button"), selected: false, label: phrase("calibrate_button")) {
                session.calibrate()
            }
            Text(phrase("hold_phone"))
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(GuideColor.yellow)
                .fixedSize(horizontal: false, vertical: true)
            Text(phrase("safety_note"))
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(GuideColor.yellow)
                .fixedSize(horizontal: false, vertical: true)
            option(title: phrase("about_button"), selected: false, label: phrase("about_button")) {
                session.showAbout = true
            }
        }
    }

    private func choice(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 22, weight: .bold))
            .foregroundColor(GuideColor.yellow)
            .padding(.top, 8)
            .accessibilityAddTraits(.isHeader)
    }

    private func option(title: String, selected: Bool, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.black)
                .frame(maxWidth: .infinity, minHeight: 56)
                .padding(.horizontal, 12)
                .background(selected ? Color.white : GuideColor.yellow)
                .cornerRadius(12)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var bigTitle: String {
        if !session.cameraAllowed && AVCaptureDevice.authorizationStatus(for: .video) == .denied {
            return phrase("action_open_settings")
        }
        if !session.cameraAllowed && !session.guiding {
            return phrase("action_allow_camera")
        }
        return session.guiding ? phrase("action_stop") : phrase("action_start")
    }

    private var bigAccessLabel: String {
        if !session.cameraAllowed && AVCaptureDevice.authorizationStatus(for: .video) == .denied {
            return phrase("cd_open_settings")
        }
        if !session.cameraAllowed && !session.guiding {
            return phrase("cd_allow_camera")
        }
        return session.guiding ? phrase("cd_stop") : phrase("cd_start")
    }

    private func phrase(_ key: String) -> String {
        PhraseBook.text(key, language: session.settings.languageTag)
    }

    private func phrase(_ key: String, _ arg: String) -> String {
        PhraseBook.text(key, language: session.settings.languageTag, arg)
    }
}

private enum GuideSettingsWake {
    static let defaultPhrase = "Hey Guide"
}

private enum GuideColor {
    static let background = Color.black
    static let yellow = Color(red: 1, green: 0.86, blue: 0)
}

private struct DoubleTapListen: ViewModifier {
    var enabled: Bool
    var action: () -> Void

    func body(content: Content) -> some View {
        if enabled {
            content.simultaneousGesture(TapGesture(count: 2).onEnded { action() })
        } else {
            content
        }
    }
}
