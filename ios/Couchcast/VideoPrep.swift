import AVFoundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// A video picked from the Photos library, copied to a temporary file the app can serve.
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let dest = FileManager.default.temporaryDirectory.appendingPathComponent("pick-\(UUID().uuidString).\(ext)")
            try FileManager.default.copyItem(at: received.file, to: dest)
            return PickedMovie(url: dest)
        }
    }
}

enum VideoPrepError: Error { case unsupported, failed }

enum VideoPrep {
    /// iPhones record .mov, often HEVC, which many Rokus can't play. Anything that isn't already an .mp4 is re-encoded
    /// as an H.264 MP4 (1080p at most) with the index at the front so the Roku can start and seek quickly.
    static func playableMP4(from url: URL) async throws -> URL {
        let ext = url.pathExtension.lowercased()
        if ext == "mp4" || ext == "m4v" { return url }
        let asset = AVURLAsset(url: url)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1920x1080) else { throw VideoPrepError.unsupported }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("cast-\(UUID().uuidString).mp4")
        export.outputURL = out
        export.outputFileType = .mp4
        export.shouldOptimizeForNetworkUse = true
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            export.exportAsynchronously { cont.resume() }
        }
        guard export.status == .completed else { throw export.error ?? VideoPrepError.failed }
        return out
    }
}
