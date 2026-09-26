import Foundation

/// Apakah elemen yang sedang fokus di Mac menerima ketikan, supaya HP bisa membuka keyboard sendiri.
public enum TextFocus {
    /// Kolom teks biasa, password, dan pencarian (`AXTextField` dengan subrole), area teks (termasuk
    /// Terminal dan editor web), serta combo box yang bisa diketik.
    public static let textRoles: Set<String> = ["AXTextField", "AXTextArea", "AXComboBox"]

    public static func isTextRole(_ role: String?) -> Bool {
        role.map(textRoles.contains) ?? false
    }
}

/// Meredam kedipan status fokus. Masuk ke kolom teks langsung dilaporkan; keluar baru dilaporkan setelah
/// bertahan `releaseDelay`, supaya pindah dari satu kolom ke kolom lain tidak menutup lalu membuka keyboard HP.
public struct TextFocusFilter {
    public let releaseDelay: TimeInterval
    public private(set) var reported: Bool?
    private var leftAt: TimeInterval?

    public init(releaseDelay: TimeInterval = 0.5) {
        self.releaseDelay = releaseDelay
    }

    /// Hasil satu pemeriksaan pada waktu `now` (detik, monoton). Mengembalikan status yang perlu dikirim
    /// ke HP, atau nil kalau tidak ada yang berubah. Status pertama selalu dilaporkan.
    public mutating func update(_ focused: Bool, now: TimeInterval) -> Bool? {
        guard !focused, reported == true else {
            leftAt = nil
            return report(focused)
        }
        let since = leftAt ?? now
        leftAt = since
        guard now - since >= releaseDelay else { return nil }
        leftAt = nil
        return report(false)
    }

    private mutating func report(_ value: Bool) -> Bool? {
        guard value != reported else { return nil }
        reported = value
        return value
    }
}
