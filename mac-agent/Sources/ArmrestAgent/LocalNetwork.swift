import AppKit
import CoreImage.CIFilterBuiltins
import Foundation
import SystemConfiguration

enum LocalNetwork {
    /// Nama komputer seperti di System Settings › General › Sharing.
    static var computerName: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? Host.current().localizedName ?? "Mac"
    }

    /// IPv4 LAN utama: en0/en1 (WiFi/Ethernet) didahulukan daripada VPN atau bridge.
    static func primaryIPv4() -> String? {
        primaryInterface()?.address
    }

    /// Alamat hardware antarmuka LAN utama, untuk Wake-on-LAN. nil untuk antarmuka tanpa alamat hardware (VPN).
    static func primaryMACAddress() -> String? {
        guard let name = primaryInterface()?.name else { return nil }
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            guard let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_LINK),
                  String(cString: entry.pointee.ifa_name) == name else { continue }
            let link = address.withMemoryRebound(to: sockaddr_dl.self, capacity: 1) { $0.pointee }
            guard link.sdl_alen == 6, let dataOffset = MemoryLayout<sockaddr_dl>.offset(of: \.sdl_data) else { return nil }
            // Alamat hardware ada di sdl_data, setelah nama antarmuka (sdl_nlen byte).
            let bytes = UnsafeRawPointer(address) + dataOffset + Int(link.sdl_nlen)
            return MacAddress.format((0 ..< 6).map { bytes.load(fromByteOffset: $0, as: UInt8.self) })
        }
        return nil
    }

    private static func primaryInterface() -> (name: String, address: String)? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }

        var candidates: [(name: String, address: String)] = []
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(entry.pointee.ifa_flags)
            guard let address = entry.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  flags & (IFF_UP | IFF_RUNNING) == (IFF_UP | IFF_RUNNING),
                  flags & IFF_LOOPBACK == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let text = String(cString: host)
            if text.hasPrefix("169.254.") { continue }
            candidates.append((String(cString: entry.pointee.ifa_name), text))
        }
        return candidates.min { rank($0.name) < rank($1.name) }
    }

    private static func rank(_ interface: String) -> Int {
        interface.hasPrefix("en") ? Int(interface.dropFirst(2)) ?? 50 : 100
    }
}

enum QRCode {
    static func image(for text: String, scale: CGFloat = 8) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)) else { return nil }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }
}
