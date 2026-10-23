import SwiftUI

@main
struct RaahiSenseApp: App {
    @StateObject private var session = GuideSession()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(session)
                .preferredColorScheme(.dark)
                .onChange(of: scenePhase, perform: { phase in
                    session.setInForeground(phase != .background)
                })
        }
    }
}
