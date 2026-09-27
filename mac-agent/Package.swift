// swift-tools-version: 6.0
import PackageDescription

// Kode yang menyentuh Network/AppKit berjalan di satu antrean (main), jadi memakai mode Swift 5
// supaya tidak perlu anotasi isolasi di setiap callback.
let swift5: [SwiftSetting] = [.swiftLanguageMode(.v5)]

let package = Package(
    name: "Armrest",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "Armrest", targets: ["ArmrestAgent"]),
    ],
    targets: [
        // Logika murni tanpa AppKit: protokol, PointerMath, sesi, penyimpanan. Bisa di-unit test.
        .target(name: "AgentCore"),
        // Server WebSocket (TLS opsional) + Bonjour di atas Network.framework.
        .target(name: "AgentServer", dependencies: ["AgentCore"], swiftSettings: swift5),
        // App menu bar: SwiftUI, CGEvent, Keychain.
        .executableTarget(
            name: "ArmrestAgent",
            dependencies: ["AgentCore", "AgentServer"],
            swiftSettings: swift5
        ),
        .testTarget(name: "AgentCoreTests", dependencies: ["AgentCore"]),
        .testTarget(name: "AgentServerTests", dependencies: ["AgentServer", "AgentCore"], swiftSettings: swift5),
    ]
)
