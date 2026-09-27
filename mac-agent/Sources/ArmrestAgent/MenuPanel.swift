import AgentCore
import AgentServer
import AppKit
import SwiftUI

/// Panel menu bar (mockup B2).
struct MenuPanel: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            if let error = model.startupError {
                notice(error)
            } else if case let .failed(message) = model.serverState {
                notice(String(localized: "The server can't start: \(message)"))
            }
            ForEach(model.activeDevices) { device in
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Controlled by \(device.name)").font(.system(size: 13, weight: .bold))
                        Text(model.screenViewers.contains(device.id) ? LocalizedStringKey("Active session · viewing screen") : LocalizedStringKey("Active session"))
                            .font(.system(size: 12))
                    }
                    Spacer()
                    Button("Disconnect") { model.disconnect(device) }
                }
                .foregroundColor(Palette.warning)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 9).fill(Palette.warningFill))
            }
            Button {
                model.showPairing()
            } label: {
                Text("Add device…").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.accent)
            .controlSize(.large)
            .keyboardShortcut("n")
            .disabled(model.listeningAddress == nil)

            Text("TRUSTED DEVICES")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            if model.devices.isEmpty {
                Text("No devices yet.").font(.system(size: 12)).foregroundStyle(.secondary)
            }
            ForEach(model.devices) { device in
                deviceRow(device)
            }

            Divider()
            Toggle("Open at login", isOn: Binding(get: { model.launchAtLogin }, set: { model.setLaunchAtLogin($0) }))
                .toggleStyle(.switch)
                .controlSize(.small)
                .font(.system(size: 13))
            HStack {
                Text("Accessibility permission").font(.system(size: 13))
                Spacer()
                if model.accessibilityGranted {
                    Label("Allowed", systemImage: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Palette.success)
                } else {
                    Button("Grant permission…") { model.showOnboarding() }
                }
            }
            HStack {
                Text("Screen Recording permission").font(.system(size: 13))
                    .help("To show the Mac's screen on your phone")
                Spacer()
                if model.screenRecordingGranted {
                    Label("Allowed", systemImage: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Palette.success)
                } else {
                    Button("Allow…") { model.openScreenRecordingSettings() }
                }
            }
            Divider()
            Button("Quit") { model.quit() }
                .buttonStyle(.plain)
                .keyboardShortcut("q")
        }
        .padding(12)
        .frame(width: 320)
    }

    private var header: some View {
        HStack(spacing: 10) {
            AppGlyph()
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: "Armrest").font(.system(size: 13, weight: .bold))
                Text(status).font(.system(size: 12)).foregroundStyle(.secondary)
            }
        }
    }

    private var status: String {
        if !model.activeDevices.isEmpty { return String(format: String(localized: "Active sessions: %lld"), model.activeDevices.count) }
        if model.pairing != nil { return String(localized: "Waiting for pairing…") }
        if let address = model.listeningAddress { return String(localized: "Ready · \(address)") }
        return String(localized: "Starting…")
    }

    private func deviceRow(_ device: TrustedDevice) -> some View {
        let active = model.activeDevices.contains { $0.id == device.id }
        return HStack(spacing: 10) {
            Image(systemName: "iphone").frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name).font(.system(size: 13, weight: .semibold))
                Text(active ? String(localized: "Active now") : lastSeen(device))
                    .font(.system(size: 12, weight: active ? .semibold : .regular))
                    .foregroundColor(active ? Palette.warning : .secondary)
            }
            Spacer()
            Button("Revoke…") { model.revoke(device) }
        }
    }

    private func lastSeen(_ device: TrustedDevice) -> String {
        guard let date = device.lastSeen else { return String(localized: "Never connected") }
        let formatter = RelativeDateTimeFormatter()
        // Bahasa yang sama dengan teks app, bukan sekadar region Mac.
        formatter.locale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "en")
        let when = formatter.localizedString(for: date, relativeTo: Date())
        return String(localized: "Last seen \(when)")
    }

    private func notice(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12))
            .foregroundColor(Palette.warning)
            .fixedSize(horizontal: false, vertical: true)
    }
}
