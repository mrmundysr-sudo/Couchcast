import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

public struct PixelSize: Equatable {
    public let width: Int
    public let height: Int
    public init(_ width: Int, _ height: Int) { self.width = width; self.height = height }
}

/// Photos go to the Roku as JPEG no larger than 1080p, upright: phone photos are often HEIC or 12+ megapixels.
public enum PhotoSize {
    public static let longSide = 1920
    public static let shortSide = 1080

    /// Fits 1920x1080 whichever way the photo is turned. Never enlarges; keeps the aspect ratio.
    public static func fit(width: Int, height: Int) -> PixelSize {
        guard width > 0, height > 0 else { return PixelSize(width, height) }
        let long = Double(max(width, height)), short = Double(min(width, height))
        let scale = min(1.0, min(Double(longSide) / long, Double(shortSide) / short))
        if scale >= 1.0 { return PixelSize(width, height) }
        return PixelSize(max(1, Int((Double(width) * scale).rounded())), max(1, Int((Double(height) * scale).rounded())))
    }
}

public enum PhotoPrep {
    /// JPEG bytes for any image the system can decode (JPEG, PNG, HEIC, GIF...), or nil when it can't be read.
    public static func jpegData(from data: Data, quality: Double = 0.9) -> Data? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int else { return nil }
        let target = PhotoSize.fit(width: w, height: h)
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,           // apply the EXIF rotation
            kCGImageSourceThumbnailMaxPixelSize: min(max(target.width, target.height), max(w, h)),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        let width = cg.width, height = cg.height
        // JPEG has no transparency: put the picture on white so a transparent PNG doesn't turn black.
        guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let flat = ctx.makeImage() else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, flat, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }
}
