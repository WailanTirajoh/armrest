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
                            Text("QR code no longer valid").font(.system(size: 14, weight: .bold))
                            Text("Each QR code works once and only for 120 seconds. Create a new one to pair a phone.")
                                .font(.system(size: 13))
                                .foregroundStyle(.secondary)
                        } else {
                            step(1, "Open Armrest on your Android phone.")
                            step(2, "Tap **Pair new computer**.")
                            step(3, "Point the camera at this QR code, then click **Allow** on the Mac.")
                            countdown(pairing)
                        }
                        Text(verbatim: "\(model.hostName) · \(pairing.address)")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                        Text("Fingerprint: \(shortFingerprint)")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                    .frame(width: 250, alignment: .leading)
                }
                HStack {
                    Spacer()
                    Button(pairing.expired ? LocalizedStringKey("New QR code") : LocalizedStringKey("Regenerate")) { model.showPairing() }
                    Button("Close") { model.closePairing() }
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
                Text("Expired")
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
            Text(verbatim: String(number))
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
                Text("Valid for").foregroundStyle(.secondary)
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
            Text("Allow “\(device.name)” to control this Mac?")
                .font(.system(size: 14, weight: .bold))
                .multilineTextAlignment(.center)
            Text("\(device.name) will be able to move the cursor, click, and scroll on this Mac. You can revoke access anytime from the menu bar.")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Text("Device key: \(keySummary)")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
            VStack(spacing: 8) {
                Button { onDecision(true) } label: { Text("Allow").frame(maxWidth: .infinity) }
                    .keyboardShortcut(.defaultAction)
                Button { onDecision(false) } label: { Text("Deny").frame(maxWidth: .infinity) }
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
                Text("Ready to go").font(.system(size: 20, weight: .bold))
                Label("Accessibility permission is on", systemImage: "checkmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Palette.success)
                Text("Next: click the app icon in the menu bar, then choose **Add device** to pair your Android phone.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                Text("This app lives in the menu bar and doesn't appear in the Dock.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                HStack {
                    Spacer()
                    Button("Done") { model.closeOnboarding() }.keyboardShortcut(.defaultAction)
                }
            } else {
                Text("One more permission").font(.system(size: 20, weight: .bold))
                Text("macOS needs the Accessibility permission so Armrest can move the cursor and click when your paired phone asks it to.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: 10) {
                    step(1, "Click **Open System Settings**.")
                    step(2, "Turn on Armrest under Privacy & Security › Accessibility.")
                    step(3, "Come back here. The status below updates on its own.")
                }
                Label("Waiting for permission…", systemImage: "clock")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Palette.warning)
                HStack {
                    Spacer()
                    Button("Not now") { model.closeOnboarding() }
                    Button("Open System Settings") { model.openAccessibilitySettings() }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(28)
        .frame(width: 480)
    }

    private func step(_ number: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(verbatim: String(number))
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(.white)
                .frame(width: 20, height: 20)
                .background(Circle().fill(Palette.accent))
            Text(text).font(.system(size: 13)).fixedSize(horizontal: false, vertical: true)
        }
    }
}
