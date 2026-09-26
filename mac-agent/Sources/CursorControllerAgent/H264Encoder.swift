import AgentCore
import CoreMedia
import Foundation
import VideoToolbox

/// Satu frame hasil encoder.
struct EncodedFrame {
    let seq: UInt32
    let keyframe: Bool
    /// Access unit dalam Annex B.
    let data: Data
    /// SPS dan PPS dalam Annex B; hanya ada di keyframe.
    let parameterSets: Data?
}

enum H264EncoderError: Error {
    case create(OSStatus)
}

/// Encoder H.264 hardware (VideoToolbox) untuk video layar: rate control latensi rendah dan tanpa B-frame,
/// jadi setiap frame bisa langsung ditampilkan HP begitu diterima.
final class H264Encoder {
    let size: PixelSize
    private let session: VTCompressionSession
    private let output: (EncodedFrame) -> Void

    /// `output` dipanggil di thread VideoToolbox, urut sesuai `encode`. Frame yang dibuang encoder tidak dilaporkan.
    init(size: PixelSize, output: @escaping (EncodedFrame) -> Void) throws {
        self.size = size
        self.output = output
        session = try Self.makeSession(size: size)
        let properties: [CFString: Any] = [
            kVTCompressionPropertyKey_RealTime: true,
            kVTCompressionPropertyKey_ProfileLevel: kVTProfileLevel_H264_ConstrainedHigh_AutoLevel,
            kVTCompressionPropertyKey_AllowFrameReordering: false,
            kVTCompressionPropertyKey_AverageBitRate: ScreenSizing.bitrate(for: size),
            kVTCompressionPropertyKey_ExpectedFrameRate: ScreenSizing.fps,
            // Hanya untuk encoder biasa: mode latensi rendah membuat keyframe saat diminta saja, dan HP memintanya
            // setiap decoder-nya dibuat ulang atau tertinggal.
            kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration: 10,
            kVTCompressionPropertyKey_ColorPrimaries: kCVImageBufferColorPrimaries_ITU_R_709_2,
            kVTCompressionPropertyKey_TransferFunction: kCVImageBufferTransferFunction_ITU_R_709_2,
            kVTCompressionPropertyKey_YCbCrMatrix: kCVImageBufferYCbCrMatrix_ITU_R_709_2,
        ]
        // Properti yang tidak didukung encoder tertentu dilewati; sisanya tetap berlaku.
        for (key, value) in properties {
            VTSessionSetProperty(session, key: key, value: value as CFTypeRef)
        }
        VTCompressionSessionPrepareToEncodeFrames(session)
    }

    func encode(_ buffer: CVPixelBuffer, seq: UInt32, keyframe: Bool) {
        let properties = keyframe ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
        let output = self.output
        VTCompressionSessionEncodeFrame(
            session, imageBuffer: buffer, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
            duration: .invalid, frameProperties: properties, infoFlagsOut: nil
        ) { status, _, sample in
            guard status == noErr, let sample, let frame = Self.frame(from: sample, seq: seq) else { return }
            output(frame)
        }
    }

    func invalidate() {
        VTCompressionSessionInvalidate(session)
    }

    private static func makeSession(size: PixelSize) throws -> VTCompressionSession {
        // Mode latensi rendah tersedia di Apple Silicon; tanpa itu, encoder biasa tetap dipakai.
        let specifications: [CFDictionary?] = [
            [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true] as CFDictionary,
            nil,
        ]
        var status: OSStatus = noErr
        for specification in specifications {
            var session: VTCompressionSession?
            status = VTCompressionSessionCreate(
                allocator: nil, width: Int32(size.width), height: Int32(size.height), codecType: kCMVideoCodecType_H264,
                encoderSpecification: specification, imageBufferAttributes: nil, compressedDataAllocator: nil,
                outputCallback: nil, refcon: nil, compressionSessionOut: &session
            )
            if status == noErr, let session { return session }
        }
        throw H264EncoderError.create(status)
    }

    private static func frame(from sample: CMSampleBuffer, seq: UInt32) -> EncodedFrame? {
        guard let block = CMSampleBufferGetDataBuffer(sample), let format = CMSampleBufferGetFormatDescription(sample),
              let parameters = parameterSets(of: format) else { return nil }
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[String: Any]]
        let keyframe = attachments?.first?[kCMSampleAttachmentKey_NotSync as String] as? Bool != true
        let length = CMBlockBufferGetDataLength(block)
        var bytes = Data(count: length)
        let copied = bytes.withUnsafeMutableBytes { raw in
            CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: raw.baseAddress!)
        }
        guard copied == kCMBlockBufferNoErr, let data = AnnexB.fromLengthPrefixed(bytes, lengthSize: parameters.lengthSize) else {
            return nil
        }
        return EncodedFrame(seq: seq, keyframe: keyframe, data: data, parameterSets: keyframe ? parameters.units : nil)
    }

    /// SPS dan PPS (Annex B) dari format encoder, plus ukuran awalan panjang NAL di data frame.
    private static func parameterSets(of format: CMFormatDescription) -> (units: Data, lengthSize: Int)? {
        var count = 0
        var lengthSize: Int32 = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
            parameterSetCountOut: &count, nalUnitHeaderLengthOut: &lengthSize
        ) == noErr else { return nil }
        var units: [Data] = []
        for index in 0..<count {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: index, parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil
            ) == noErr, let pointer else { return nil }
            units.append(Data(bytes: pointer, count: size))
        }
        return (AnnexB.join(units), Int(lengthSize))
    }
}
