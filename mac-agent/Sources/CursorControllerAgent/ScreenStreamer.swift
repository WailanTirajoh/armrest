import AgentCore
import CoreVideo
import Foundation

/// Satu aliran layar ke satu HP: gambar dari `source` dikompres H.264 lalu dikirim sebagai `ScreenPacket`.
/// Paling banyak `ScreenFlowControl.window` frame boleh belum dikonfirmasi HP. Selama penuh, hanya gambar
/// terbaru yang disimpan, lalu dikirim begitu ada konfirmasi.
final class ScreenStreamer {
    private let source: ScreenSource
    private let send: (Data) -> Void
    private let report: (ScreenStatus) -> Void
    private let queue = DispatchQueue(label: "io.github.wailantirajoh.cursorcontroller.screen", qos: .userInteractive)

    // Hanya diakses di `queue`.
    private var encoder: H264Encoder?
    /// Naik setiap encoder dibuat ulang; keluaran encoder lama dibuang.
    private var encoderGeneration = 0
    private var flow = ScreenFlowControl()
    /// Gambar terbaru: dikirim nanti kalau antrean penuh, atau dikirim ulang sebagai keyframe saat layar diam.
    private var latest: CVPixelBuffer?
    private var latestSent = true
    private var keyframeNeeded = true
    private var stopped = false

    /// `send` dan `report` dipanggil di antrean internal streamer.
    init(source: ScreenSource, send: @escaping (Data) -> Void, report: @escaping (ScreenStatus) -> Void) {
        self.source = source
        self.send = send
        self.report = report
    }

    func start(_ request: ScreenRequest) {
        queue.async {
            self.source.start(request, queue: self.queue) { [weak self] event in self?.handle(event) }
        }
    }

    /// HP meminta lagi: decoder-nya butuh keyframe, mungkin dengan ukuran baru.
    func update(_ request: ScreenRequest) {
        queue.async {
            guard !self.stopped else { return }
            self.keyframeNeeded = true
            self.source.update(request)
            self.encodeLatest()
        }
    }

    func ack(_ seq: UInt32) {
        queue.async {
            self.flow.ack(seq)
            self.encodeLatest()
        }
    }

    func stop() {
        queue.async { self.shutdown() }
    }

    private func handle(_ event: ScreenSourceEvent) {
        guard !stopped else { return }
        switch event {
        case .started:
            report(.streaming)
        case let .size(size):
            guard encoder?.size != size else { return }
            encoder?.invalidate()
            encoderGeneration += 1
            let generation = encoderGeneration
            do {
                encoder = try H264Encoder(size: size) { [weak self] frame in
                    self?.queue.async { self?.deliver(frame, size: size, generation: generation) }
                }
            } catch {
                shutdown()
                report(.failed)
                return
            }
            keyframeNeeded = true
            encodeLatest()
        case let .frame(buffer):
            latest = buffer
            latestSent = false
            encodeLatest()
        case let .stopped(error):
            shutdown()
            report(error?.isScreenRecordingDenied == true ? .denied : .failed)
        }
    }

    private func encodeLatest() {
        guard !stopped, let encoder, let buffer = latest, !latestSent || keyframeNeeded, flow.canSend,
              CVPixelBufferGetWidth(buffer) == encoder.size.width, CVPixelBufferGetHeight(buffer) == encoder.size.height
        else { return }
        encoder.encode(buffer, seq: flow.next(), keyframe: keyframeNeeded)
        keyframeNeeded = false
        latestSent = true
    }

    private func deliver(_ frame: EncodedFrame, size: PixelSize, generation: Int) {
        guard !stopped, generation == encoderGeneration else { return }
        if let parameterSets = frame.parameterSets {
            send(ScreenPacket.config(width: size.width, height: size.height, parameterSets: parameterSets).encoded())
        }
        send(ScreenPacket.frame(seq: frame.seq, keyframe: frame.keyframe, data: frame.data).encoded())
    }

    private func shutdown() {
        guard !stopped else { return }
        stopped = true
        source.stop()
        encoder?.invalidate()
        encoder = nil
        latest = nil
    }
}
