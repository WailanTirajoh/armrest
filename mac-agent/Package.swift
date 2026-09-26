// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CursorControllerAgent",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "CursorControllerAgent", targets: ["CursorControllerAgent"]),
    ],
    targets: [
        // Logika murni tanpa AppKit: protokol, PointerMath, sesi, penyimpanan. Bisa di-unit test.
        .target(name: "AgentCore"),
        // App menu bar: SwiftUI, CGEvent, Network.framework, Keychain.
        .executableTarget(name: "CursorControllerAgent", dependencies: ["AgentCore"]),
        .testTarget(name: "AgentCoreTests", dependencies: ["AgentCore"]),
    ]
)
