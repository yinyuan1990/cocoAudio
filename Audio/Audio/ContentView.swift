import SwiftUI

// 方案A（iOS 风）配色
private let bgColor = Color(red: 0.949, green: 0.949, blue: 0.969)
private let green = Color(red: 0.204, green: 0.780, blue: 0.349)
private let red = Color(red: 1.0, green: 0.231, blue: 0.188)
private let blue = Color(red: 0.0, green: 0.478, blue: 1.0)
private let keyFill = Color(white: 0.47, opacity: 0.12)
private let ink = Color(red: 0.11, green: 0.11, blue: 0.12)
private let sub = Color(red: 0.557, green: 0.557, blue: 0.576)

struct ContentView: View {
    @StateObject private var ws = WSClient.shared
    @StateObject private var controller = CallController()
    @State private var deviceId = ""
    @State private var showSettings = false

    private var inCall: Bool { ws.callState == .calling || ws.callState == .inCall }
    private var online: Bool { if let p = ws.deviceOnline { return p.id == deviceId && p.online }; return false }

    var body: some View {
        ZStack {
            bgColor.ignoresSafeArea()
            if inCall {
                InCallView(deviceId: deviceId, connected: ws.callState == .inCall,
                           onMute: { controller.setMuted($0) },
                           onEnd: { ws.endCall() })
            } else {
                DialerView(deviceId: $deviceId, online: online,
                           onCall: { if deviceId.count >= 6 { ws.ensureConnected(); ws.callDevice(deviceId) } },
                           onSettings: { if deviceId.count >= 6 { showSettings = true } },
                           onWifi: { if deviceId.count >= 6 { ws.requestWifiScan(deviceId) } })
            }
        }
        .onChange(of: deviceId) { _ in if deviceId.count >= 6 { ws.ensureConnected(); ws.checkDeviceStatus(deviceId) } }
        .onChange(of: ws.callState) { state in
            if state == .inCall { controller.startAudio() }
            else if case .ended = state { controller.stopAudio() }
            else if state == .idle { controller.stopAudio() }
        }
        .sheet(isPresented: $showSettings) { SettingsView(deviceId: deviceId) }
    }
}

private struct DialerView: View {
    @Binding var deviceId: String
    let online: Bool
    let onCall: () -> Void
    let onSettings: () -> Void
    let onWifi: () -> Void

    private let keys: [[(String, String)]] = [
        [("1", ""), ("2", "ABC"), ("3", "DEF")],
        [("4", "GHI"), ("5", "JKL"), ("6", "MNO")],
        [("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ")],
        [("*", ""), ("0", "+"), ("#", "")]
    ]

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                circleButton("gearshape") { onSettings() }
                Spacer()
                presencePill
                Spacer()
                circleButton("wifi") { onWifi() }
            }
            .padding(.horizontal, 20).padding(.top, 12)

            Text("心声").font(.system(size: 22, weight: .bold)).foregroundColor(blue).padding(.top, 8)
            Text("远程语音对讲").font(.system(size: 12)).foregroundColor(sub)

            Text(deviceId.isEmpty ? "输入设备 ID" : deviceId)
                .font(.system(size: deviceId.isEmpty ? 22 : 40, weight: .semibold))
                .foregroundColor(deviceId.isEmpty ? sub : ink)
                .padding(.vertical, 22)

            ForEach(0..<keys.count, id: \.self) { r in
                HStack(spacing: 26) {
                    ForEach(0..<keys[r].count, id: \.self) { c in
                        dialKey(keys[r][c].0, keys[r][c].1)
                    }
                }.padding(.vertical, 8)
            }

            Spacer()

            ZStack {
                Button(action: onCall) {
                    ZStack { Circle().fill(green).frame(width: 74, height: 74)
                        Image(systemName: "phone.fill").font(.system(size: 30)).foregroundColor(.white) }
                }
                HStack {
                    Spacer()
                    Button { if !deviceId.isEmpty { deviceId.removeLast() } } label: {
                        Image(systemName: "delete.left").font(.system(size: 24)).foregroundColor(ink)
                    }
                    .padding(.trailing, 44)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 40)
        }
    }

    private var presencePill: some View {
        let c = online ? green : red
        return HStack(spacing: 8) {
            Circle().fill(c).frame(width: 8, height: 8)
            Text(online ? "设备在线" : "不在线").font(.system(size: 13, weight: .semibold)).foregroundColor(c)
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(c.opacity(0.12)).clipShape(Capsule())
    }

    private func dialKey(_ d: String, _ l: String) -> some View {
        Button { if deviceId.count < 10 { deviceId += d } } label: {
            ZStack {
                Circle().fill(keyFill).frame(width: 74, height: 74)
                VStack(spacing: 2) {
                    Text(d).font(.system(size: 30)).foregroundColor(ink)
                    if !l.isEmpty { Text(l).font(.system(size: 9, weight: .semibold)).foregroundColor(sub) }
                }
            }
        }
    }

    private func circleButton(_ symbol: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            ZStack { Circle().fill(Color.white).frame(width: 42, height: 42)
                Image(systemName: symbol).font(.system(size: 18)).foregroundColor(blue) }
        }
    }
}

private struct InCallView: View {
    let deviceId: String
    let connected: Bool
    let onMute: (Bool) -> Void
    let onEnd: () -> Void
    @State private var mic = false
    @State private var spk = true

    var body: some View {
        ZStack {
            Color(white: 0.11).ignoresSafeArea()
            VStack {
                Spacer().frame(height: 96)
                Text(deviceId).font(.system(size: 34, weight: .medium)).foregroundColor(.white)
                Text(connected ? "通话中" : "正在呼叫…").font(.system(size: 16)).foregroundColor(Color(white: 0.9, opacity: 0.6)).padding(.top, 10)
                Spacer()
                HStack(spacing: 34) {
                    ctrl("mic.slash.fill", "静音", mic) { mic.toggle(); onMute(mic) }
                    ctrl("speaker.wave.2.fill", "免提", spk) { spk.toggle() }
                }
                Spacer().frame(height: 30)
                Button(action: onEnd) {
                    ZStack { Circle().fill(red).frame(width: 74, height: 74)
                        Image(systemName: "phone.down.fill").font(.system(size: 28)).foregroundColor(.white) }
                }
                Spacer().frame(height: 46)
            }
        }
    }

    private func ctrl(_ symbol: String, _ label: String, _ active: Bool, _ action: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Button(action: action) {
                ZStack {
                    Circle().fill(active ? Color.white : Color.white.opacity(0.2)).frame(width: 64, height: 64)
                    Image(systemName: symbol).font(.system(size: 24)).foregroundColor(active ? ink : .white)
                }
            }
            Text(label).font(.system(size: 12)).foregroundColor(Color(white: 0.9, opacity: 0.85))
        }
    }
}

private struct SettingsView: View {
    let deviceId: String
    @Environment(\.dismiss) private var dismiss
    @State private var volume: Double = 80

    var body: some View {
        NavigationView {
            Form {
                Section("咪头音量  \(Int(volume))%") {
                    Slider(value: $volume, in: 0...100, step: 5) { editing in
                        if !editing { WSClient.shared.sendVolume(deviceId, Int(volume)) }
                    }.tint(blue)
                }
                Section("联网方式") {
                    Button("切换 Wi-Fi") { WSClient.shared.sendSwitchNetwork(deviceId, "wifi") }
                    Button("切换 4G") { WSClient.shared.sendSwitchNetwork(deviceId, "4g") }
                }
                Section("设备操作") {
                    Button("配对（点按）") { WSClient.shared.sendPairingGpio(deviceId, 0) }
                    Button("复位（恢复出厂）", role: .destructive) { WSClient.shared.sendFactoryReset(deviceId) }
                }
            }
            .navigationTitle("设备设置")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
}
