import SwiftUI

struct AboutView: View {
    @EnvironmentObject private var session: GuideSession

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Button(action: { session.showAbout = false }) {
                    Text(phrase("action_back"))
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.black)
                        .frame(maxWidth: .infinity, minHeight: 64)
                        .background(Color(red: 1, green: 0.86, blue: 0))
                        .cornerRadius(12)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(phrase("action_back"))
                Text(phrase("about_title"))
                    .font(.system(size: 34, weight: .bold))
                    .foregroundColor(Color(red: 1, green: 0.86, blue: 0))
                    .accessibilityAddTraits(.isHeader)
                paragraph("about_intro")
                paragraph("about_body")
                paragraph("ios_about_models")
                paragraph("ios_limit_locked")
                paragraph("ios_limit_light")
                paragraph("ios_limit_wake")
                paragraph("ios_limit_volume")
                paragraph("ios_prepared")
                paragraph("safety_note")
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color.black.ignoresSafeArea())
    }

    private func paragraph(_ key: String) -> some View {
        Text(phrase(key))
            .font(.system(size: 20, weight: .medium))
            .foregroundColor(Color(red: 1, green: 0.86, blue: 0))
            .fixedSize(horizontal: false, vertical: true)
    }

    private func phrase(_ key: String) -> String {
        PhraseBook.text(key, language: session.settings.languageTag)
    }
}
