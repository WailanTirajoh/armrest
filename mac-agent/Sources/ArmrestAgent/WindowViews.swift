import AgentCore
import AppKit
import CryptoKit
import SwiftUI

/// Jendela QR pairing (mockup B3).
struct PairingView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let pairing = model.pairing {
                HStack(alignment: .top, spacing: 20) {
                    qr(pairing)
                    VStack(alignment: .leading, spacing: 10) {
                        if pairing.expired {
                            Text("QR sudah tidak berlaku").font(.system(size: 14, weight: .bold))
                            Text("Setiap QR hanya berlaku 120 detik dan sekali pakai. Buat QR baru untuk memasangkan HP.")
                                .font(.system(size: 13))
                                .foregroundStyle(.secondary)
                        } else {
                            step(1, "Buka Armrest di HP Android.")
                            step(2, "Ketuk **Pair komputer baru**.")
                            step(3, "Arahkan kamera ke QR ini, lalu klik **Izinkan** di Mac.")
                            countdown(pairing)
                        }
                        Text("\(model.hostName) · \(pairing.address)")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                        Text("Sidik jari: \(shortFingerprint)")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                    .frame(width: 250, alignment: .leading)
                }
                HStack {
                    Spacer()
                    Button(pairing.expired ? "Buat QR baru" : "Buat ulang") { model.showPairing() }
                    Button("Tutup") { model.closePairing() }
                        .keyboardShortcut(.cancelAction)
                }
            }
        }
        .padding(24)
    }

    private func qr(_ pairing: PairingDisplay) -> some View {
        ZStack {
            if let image = pairing.image {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 200, height: 200)
                    .opacity(pairing.expired ? 0.12 : 1)
            }
            if pairing.expired {
                Text("Kedaluwarsa")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(Palette.warning)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(Palette.warningFill))
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.white))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.3)))
    }

    private func step(_ number: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(number)")
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(.white)
                .frame(width: 20, height: 20)
                .background(Circle().fill(Palette.accent))
            Text(text).font(.system(size: 13)).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func countdown(_ pairing: PairingDisplay) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Berlaku").foregroundStyle(.secondary)
                Spacer()
                Text(String(format: "%d:%02d", pairing.secondsRemaining / 60, pairing.secondsRemaining % 60)).fontWeight(.semibold)
            }
            .font(.system(size: 12))
            ProgressView(value: Double(pairing.secondsRemaining), total: PairingTokens.ttl)
                .tint(Palette.accent)
        }
    }

    private var shortFingerprint: String {
        let fp = model.fingerprint
        guard fp.count > 12 else { return fp }
        return "\(fp.prefix(6))…\(fp.suffix(6))"
    }
}

/// Dialog "Izinkan?" untuk perangkat baru (mockup B4).
struct ApprovalView: View {
    let device: PendingDevice
    let onDecision: (Bool) -> Void

    var body: some View {
        VStack(spacing: 12) {
            AppGlyph(size: 64)
            Text("Izinkan “\(device.name)” mengontrol Mac ini?")
                .font(.system(size: 14, weight: .bold))
                .multilineTextAlignment(.center)
            Text("\(device.name) akan bisa menggerakkan kursor, klik, dan scroll di Mac ini. Akses bisa dicabut kapan saja dari menu bar.")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Text("Kunci perangkat: \(keySummary)")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
            VStack(spacing: 8) {
                Button { onDecision(true) } label: { Text("Izinkan").frame(maxWidth: .infinity) }
                    .keyboardShortcut(.defaultAction)
                Button { onDecision(false) } label: { Text("Tolak").frame(maxWidth: .infinity) }
                    .keyboardShortcut(.cancelAction)
            }
            .controlSize(.large)
            .padding(.top, 4)
        }
        .padding(22)
        .frame(width: 300)
    }

    private var keySummary: String {
        let hex = SHA256.hash(data: device.publicKey).map { String(format: "%02X", $0) }
        return "\(hex[0])\(hex[1]) \(hex[2])\(hex[3]) … \(hex[28])\(hex[29]) \(hex[30])\(hex[31])"
    }
}

/// Onboarding izin Accessibility (mockup B5).
struct OnboardingView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if model.accessibilityGranted {
                Text("Siap dipakai").font(.system(size: 20, weight: .bold))
                Label("Izin Accessibility aktif", systemImage: "checkmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Palette.success)
                Text("Berikutnya: klik ikon app di menu bar, lalu pilih **Tambah perangkat** untuk memasangkan HP Android.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                Text("App ini berjalan di menu bar dan tidak muncul di Dock.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                HStack {
                    Spacer()
                    Button("Selesai") { model.closeOnboarding() }.keyboardShortcut(.defaultAction)
                }
            } else {
                Text("Satu izin lagi").font(.system(size: 20, weight: .bold))
                Text("macOS perlu izin Accessibility supaya Armrest bisa menggerakkan kursor dan klik atas perintah HP yang sudah kamu pasangkan.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: 10) {
                    step(1, "Klik **Buka System Settings**.")
                    step(2, "Nyalakan Armrest di Privacy & Security › Accessibility.")
                    step(3, "Kembali ke sini. Status di bawah berubah otomatis.")
                }
                Label("Menunggu izin…", systemImage: "clock")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Palette.warning)
                HStack {
                    Spacer()
                    Button("Nanti saja") { model.closeOnboarding() }
                    Button("Buka System Settings") { model.openAccessibilitySettings() }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(28)
        .frame(width: 480)
    }

    private func step(_ number: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(number)")
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(.white)
                .frame(width: 20, height: 20)
                .background(Circle().fill(Palette.accent))
            Text(text).font(.system(size: 13)).fixedSize(horizontal: false, vertical: true)
        }
    }
}
