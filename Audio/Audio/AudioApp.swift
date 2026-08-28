import SwiftUI
import AVFoundation

@main
struct AudioApp: App {
    init() {
        AVAudioSession.sharedInstance().requestRecordPermission { _ in }
        WSClient.shared.connect()
    }
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
