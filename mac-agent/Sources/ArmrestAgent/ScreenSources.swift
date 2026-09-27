import AgentCore
import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

enum ScreenSourceEvent {
    /// Tangkapan berjalan.
    case started
    /// Ukuran video: sekali di awal, lalu setiap berubah (mis. kursor pindah ke monitor lain).
    case size(PixelSize)
    /// Gambar baru, dengan posisi kursor 0–1 dari kiri atas gambar (nil kalau tidak diketahui).
    /// Layar yang diam tidak menghasilkan gambar.
    case frame(CVPixelBuffer, cursor: CGPoint?)
    /// Berhenti sendiri karena error, termasuk gagal mulai. Tidak ada event lain sesudahnya.
    case stopped(Error?)
}

/// Sumber gambar untuk `ScreenStreamer`. Semua method dan event berjalan di antrean milik streamer.
protocol ScreenSource: AnyObject {
    func start(_ request: ScreenRequest, queue: DispatchQueue, events: @escaping (ScreenSourceEvent) -> Void)
    func update(_ request: ScreenRequest)
    func stop()
}

enum ScreenCaptureError: Error {
    case noDisplay
}

extension Error {
    /// Mac belum memberi izin Screen Recording.
    var isScreenRecordingDenied: Bool {
        let error = self as NSError
        return error.domain == SCStreamErrorDomain && error.code == SCStreamError.Code.userDeclined.rawValue
    }
}

/// Tangkapan layar sungguhan lewat ScreenCaptureKit, termasuk kursor. Mengikuti monitor tempat kursor berada.
final class DisplayCapture: NSObject, ScreenSource, SCStreamOutput, SCStreamDelegate {
    // Semua state hanya diakses di `queue`.
    private var queue: DispatchQueue?
    private var events: ((ScreenSourceEvent) -> Void)?
    private var request = ScreenRequest(maxWidth: ScreenSizing.maxLongSide, maxHeight: ScreenSizing.maxShortSide)
    private var stream: SCStream?
    private var displayID: CGDirectDisplayID?
    private var size: PixelSize?
    private var followTimer: DispatchSourceTimer?
    private var switching = false
    private var stopped = false

    func start(_ request: ScreenRequest, queue: DispatchQueue, events: @escaping (ScreenSourceEvent) -> Void) {
        self.queue = queue
        self.events = events
        self.request = request
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: true) { content, error in
            queue.async { self.begin(content?.displays ?? [], error: error) }
        }
    }

    func update(_ request: ScreenRequest) {
        self.request = request
        guard let stream, let displayID else { return }
        let size = Self.videoSize(of: displayID, request: request)
        guard size != self.size else { return }
        stream.updateConfiguration(Self.configuration(size)) { error in
            self.queue?.async {
                guard !self.stopped, error == nil else { return }
                self.size = size
                self.events?(.size(size))
            }
        }
    }

    func stop() {
        guard !stopped else { return }
        stopped = true
        followTimer?.cancel()
        followTimer = nil
        stream?.stopCapture { _ in }
        stream = nil
        events = nil
    }

    private func begin(_ displays: [SCDisplay], error: Error?) {
        guard !stopped else { return }
        let cursorDisplay = Self.displayUnderCursor()
        guard let display = displays.first(where: { $0.displayID == cursorDisplay }) ?? displays.first else {
            return fail(error ?? ScreenCaptureError.noDisplay)
        }
        let size = Self.videoSize(of: display.displayID, request: request)
        let stream = SCStream(
            filter: SCContentFilter(display: display, excludingWindows: []), configuration: Self.configuration(size), delegate: self
        )
        do {
            try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        } catch {
            return fail(error)
        }
        self.stream = stream
        displayID = display.displayID
        self.size = size
        events?(.size(size))
        stream.startCapture { error in
            self.queue?.async {
                guard !self.stopped else { return }
                if let error { return self.fail(error) }
                self.events?(.started)
                self.followCursor()
            }
        }
    }

    /// Dua kali sedetik: kalau kursor pindah ke monitor lain, tangkapan ikut pindah.
    private func followCursor() {
        guard let queue else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 0.5, repeating: 0.5)
        timer.setEventHandler { [weak self] in self?.checkDisplay() }
        followTimer = timer
        timer.resume()
    }

    private func checkDisplay() {
        guard !stopped, !switching, let stream, let target = Self.displayUnderCursor(), target != displayID else { return }
        switching = true
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: true) { content, _ in
            self.queue?.async {
                guard !self.stopped, let display = content?.displays.first(where: { $0.displayID == target }) else {
                    self.switching = false
                    return
                }
                let size = Self.videoSize(of: target, request: self.request)
                stream.updateContentFilter(SCContentFilter(display: display, excludingWindows: [])) { _ in
                    stream.updateConfiguration(Self.configuration(size)) { _ in
                        self.queue?.async {
                            self.switching = false
                            guard !self.stopped else { return }
                            self.displayID = target
                            if size != self.size {
                                self.size = size
                                self.events?(.size(size))
                            }
                        }
                    }
                }
            }
        }
    }

    private func fail(_ error: Error?) {
        guard !stopped else { return }
        let events = self.events
        stop()
        events?(.stopped(error))
    }

    // MARK: SCStreamOutput, SCStreamDelegate

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        // Dipanggil di `queue`. Status .idle berarti layar tidak berubah; hanya gambar baru yang diteruskan.
        guard type == .screen, !stopped, sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
                as? [[SCStreamFrameInfo: Any]],
              let status = (attachments.first?[.status] as? Int).flatMap(SCFrameStatus.init(rawValue:)), status == .complete,
              let buffer = sampleBuffer.imageBuffer else { return }
        events?(.frame(buffer, cursor: displayID.flatMap(Self.cursorPosition(on:))))
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        queue?.async { self.fail(error) }
    }

    // MARK: Bantuan

    /// Posisi kursor relatif terhadap monitor yang ditangkap, 0–1 dari kiri atas. Koordinat CGEvent dan
    /// CGDisplayBounds sama-sama global dalam point, dengan titik nol di kiri atas monitor utama.
    private static func cursorPosition(on display: CGDirectDisplayID) -> CGPoint? {
        guard let location = CGEvent(source: nil)?.location else { return nil }
        let bounds = CGDisplayBounds(display)
        guard bounds.width > 0, bounds.height > 0 else { return nil }
        return CGPoint(
            x: min(max((location.x - bounds.minX) / bounds.width, 0), 1), y: min(max((location.y - bounds.minY) / bounds.height, 0), 1)
        )
    }

    private static func displayUnderCursor() -> CGDirectDisplayID? {
        guard let location = CGEvent(source: nil)?.location else { return nil }
        var display: CGDirectDisplayID = 0
        var count: UInt32 = 0
        guard CGGetDisplaysWithPoint(location, 1, &display, &count) == .success, count > 0 else { return nil }
        return display
    }

    private static func videoSize(of display: CGDirectDisplayID, request: ScreenRequest) -> PixelSize {
        let mode = CGDisplayCopyDisplayMode(display)
        let pixels = PixelSize(
            width: mode?.pixelWidth ?? CGDisplayPixelsWide(display), height: mode?.pixelHeight ?? CGDisplayPixelsHigh(display)
        )
        return ScreenSizing.videoSize(display: pixels, request: request)
    }

    private static func configuration(_ size: PixelSize) -> SCStreamConfiguration {
        let configuration = SCStreamConfiguration()
        configuration.width = size.width
        configuration.height = size.height
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(ScreenSizing.fps))
        // Format asli encoder H.264, jadi tidak ada konversi warna tambahan.
        configuration.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        configuration.colorMatrix = kCVImageBufferYCbCrMatrix_ITU_R_709_2
        configuration.colorSpaceName = CGColorSpace.sRGB
        configuration.showsCursor = true
        // Streamer menahan satu gambar untuk keyframe ulang; sisanya cukup untuk tangkapan berikutnya.
        configuration.queueDepth = 5
        return configuration
    }
}

/// Pola uji bergerak untuk profil e2e: melewati encoder sungguhan tanpa menangkap layar pengguna.
final class TestPatternSource: ScreenSource {
    /// Seolah-olah layar Mac berukuran 1440 × 900.
    static let display = PixelSize(width: 1440, height: 900)

    private var events: ((ScreenSourceEvent) -> Void)?
    private var timer: DispatchSourceTimer?
    private var pool: CVPixelBufferPool?
    private var size = TestPatternSource.display
    private var tick = 0

    func start(_ request: ScreenRequest, queue: DispatchQueue, events: @escaping (ScreenSourceEvent) -> Void) {
        self.events = events
        resize(ScreenSizing.videoSize(display: Self.display, request: request))
        events(.started)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: 1.0 / Double(ScreenSizing.fps))
        timer.setEventHandler { [weak self] in self?.draw() }
        self.timer = timer
        timer.resume()
    }

    func update(_ request: ScreenRequest) {
        let size = ScreenSizing.videoSize(display: Self.display, request: request)
        if size != self.size { resize(size) }
    }

    func stop() {
        timer?.cancel()
        timer = nil
        events = nil
    }

    private func resize(_ size: PixelSize) {
        self.size = size
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: size.width,
            kCVPixelBufferHeightKey as String: size.height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
        ]
        pool = nil
        CVPixelBufferPoolCreate(nil, nil, attributes as CFDictionary, &pool)
        events?(.size(size))
    }

    private func draw() {
        var buffer: CVPixelBuffer?
        guard let pool, CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer) == kCVReturnSuccess, let buffer else { return }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(buffer), width: size.width, height: size.height, bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return }
        tick += 1
        let width = CGFloat(size.width)
        let height = CGFloat(size.height)
        context.setFillColor(CGColor(red: 0.12, green: 0.2, blue: 0.35, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        // Papan catur untuk memeriksa ketajaman, dan balok yang bergerak supaya setiap frame berubah.
        context.setFillColor(CGColor(gray: 0.9, alpha: 1))
        let cell = height / 12
        for row in 0..<4 {
            for column in 0..<6 where (row + column).isMultiple(of: 2) {
                context.fill(CGRect(x: width * 0.05 + CGFloat(column) * cell, y: height * 0.55 + CGFloat(row) * cell, width: cell, height: cell))
            }
        }
        let bar = width * 0.1
        let barX = CGFloat(tick % 90) / 90 * (width - bar)
        context.setFillColor(CGColor(red: 1, green: 0.62, blue: 0.2, alpha: 1))
        context.fill(CGRect(x: barX, y: height * 0.15, width: bar, height: height * 0.25))
        // Kursor tiruan di tengah balok. CGContext menghitung y dari bawah, jadi dari atas gambar y = 1 − 0,275.
        events?(.frame(buffer, cursor: CGPoint(x: (barX + bar / 2) / width, y: 0.725)))
    }
}
