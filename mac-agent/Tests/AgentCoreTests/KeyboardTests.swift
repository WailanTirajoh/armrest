import CoreGraphics
import Foundation
import Testing
@testable import AgentCore

@Test func newlinesAndTabsBecomeKeys() {
    #expect(TextChunker.pieces("Halo\ndunia\tok") == [
        .unicode("Halo"), .key(.returnKey), .unicode("dunia"), .key(.tab), .unicode("ok"),
    ])
    #expect(TextChunker.pieces("a\r\nb") == [.unicode("a"), .key(.returnKey), .unicode("b")])
}

@Test func piecesStayWithinTwentyUTF16UnitsWithoutSplittingGraphemes() {
    // 👨‍👩‍👧 = 8 unit UTF-16, 👋 = 2 unit: tidak boleh terpotong di tengah.
    let text = String(repeating: "x", count: 15) + "👨‍👩‍👧" + String(repeating: "👋", count: 12)
    let pieces = TextChunker.pieces(text)
    var rebuilt = ""
    for piece in pieces {
        guard case let .unicode(chunk) = piece else {
            Issue.record("hanya boleh ada potongan teks: \(piece)")
            continue
        }
        #expect(chunk.utf16.count <= TextChunker.maxUTF16)
        rebuilt += chunk
    }
    #expect(rebuilt == text)
    #expect(pieces.first == .unicode(String(repeating: "x", count: 15)))
}

@Test func controlCharactersAreRejected() {
    #expect(TextChunker.isAllowed("Halo 👋\n\t"))
    #expect(!TextChunker.isAllowed(""))
    #expect(!TextChunker.isAllowed("a\u{0}b"))
    #expect(!TextChunker.isAllowed("a\rb"))
    #expect(!TextChunker.isAllowed("a\u{7F}"))
}

@Test func keyCodesMatchCarbonVirtualKeys() {
    #expect(KeyCode.returnKey.virtualKey == 0x24)
    #expect(KeyCode.backspace.virtualKey == 0x33)
    #expect(KeyCode.c.virtualKey == 0x08)
    #expect(KeyCode.z.rawValue == 0x39)
    #expect(KeyCode.f12.virtualKey == 0x6F)
    #expect(KeyCode.grave.rawValue == 0x5A)
    // Setiap tombol punya virtual key sendiri.
    // Setiap tombol biasa punya virtual key sendiri; tombol media memakai event sistem.
    let virtualKeys = KeyCode.allCases.compactMap(\.virtualKey)
    #expect(Set(virtualKeys).count == KeyCode.allCases.count - 3)
    #expect([KeyCode.playPause, .nextTrack, .previousTrack].map(\.mediaKeyType) == [16, 17, 18])
    #expect(KeyCode.c.mediaKeyType == nil)
}

@Test func modifiersPressCommandFirstAndCombineFlags() {
    let modifiers: KeyModifiers = [.shift, .command]
    #expect(modifiers.keys.map(\.virtualKey) == [0x37, 0x38])
    #expect(modifiers.eventFlags == [.maskCommand, .maskShift])
    #expect(InputMessage.decode(Data([0x06, 0x22, 0x10])) == nil) // bit modifier tak dikenal
}
