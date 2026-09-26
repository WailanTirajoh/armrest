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
                notice("Server tidak bisa berjalan: \(message)")
            }
            ForEach(model.activeDevices) { device in
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Dikontrol oleh \(device.name)").font(.system(size: 13, weight: .bold))
                        Text(model.screenViewers.contains(device.id) ? "Sesi aktif · melihat layar" : "Sesi aktif")
                            .font(.system(size: 12))
                    }
                    Spacer()
                    Button("Putuskan") { model.disconnect(device) }
                }
                .foregroundColor(Palette.warning)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 9).fill(Palette.warningFill))
            }
            Button {
                model.showPairing()
            } label: {
                Text("Tambah perangkat…").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.accent)
            .controlSize(.large)
            .keyboardShortcut("n")
            .disabled(model.listeningAddress == nil)

            Text("PERANGKAT TERPERCAYA")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            if model.devices.isEmpty {
                Text("Belum ada perangkat.").font(.system(size: 12)).foregroundStyle(.secondary)
            }
            ForEach(model.devices) { device in
                deviceRow(device)
            }

            Divider()
            Toggle("Buka saat login", isOn: Binding(get: { model.launchAtLogin }, set: { model.setLaunchAtLogin($0) }))
                .toggleStyle(.switch)
                .controlSize(.small)
                .font(.system(size: 13))
            HStack {
                Text("Izin Accessibility").font(.system(size: 13))
                Spacer()
                if model.accessibilityGranted {
                    Label("Diizinkan", systemImage: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Palette.success)
                } else {
                    Button("Berikan izin…") { model.showOnboarding() }
                }
            }
            HStack {
                Text("Izin Screen Recording").font(.system(size: 13))
                    .help("Untuk menampilkan layar Mac di HP")
                Spacer()
                if model.screenRecordingGranted {
                    Label("Diizinkan", systemImage: "checkmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Palette.success)
                } else {
                    Button("Izinkan…") { model.openScreenRecordingSettings() }
                }
            }
            Divider()
            Button("Keluar") { model.quit() }
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
                Text("Cursor Controller").font(.system(size: 13, weight: .bold))
                Text(status).font(.system(size: 12)).foregroundStyle(.secondary)
            }
        }
    }

    private var status: String {
        if !model.activeDevices.isEmpty { return "\(model.activeDevices.count) sesi aktif" }
        if model.pairing != nil { return "Menunggu pairing…" }
        if let address = model.listeningAddress { return "Siap · \(address)" }
        return "Menyiapkan…"
    }

    private func deviceRow(_ device: TrustedDevice) -> some View {
        let active = model.activeDevices.contains { $0.id == device.id }
        return HStack(spacing: 10) {
            Image(systemName: "iphone").frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name).font(.system(size: 13, weight: .semibold))
                Text(active ? "Aktif sekarang" : lastSeen(device))
                    .font(.system(size: 12, weight: active ? .semibold : .regular))
                    .foregroundColor(active ? Palette.warning : .secondary)
            }
            Spacer()
            Button("Cabut…") { model.revoke(device) }
        }
    }

    private func lastSeen(_ device: TrustedDevice) -> String {
        guard let date = device.lastSeen else { return "Belum pernah terhubung" }
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: "id_ID")
        return "Terakhir: \(formatter.localizedString(for: date, relativeTo: Date()))"
    }

    private func notice(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12))
            .foregroundColor(Palette.warning)
            .fixedSize(horizontal: false, vertical: true)
    }
}
