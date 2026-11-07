import Foundation

/// HTTP `Range: bytes=...` for files the phone serves. One range only; anything odd is served whole.
public enum ByteRange: Equatable {
    case full
    case unsatisfiable
    case partial(start: Int64, end: Int64)

    public static func parse(_ header: String?, length: Int64) -> ByteRange {
        guard length >= 0, let header = header else { return .full }
        let h = header.trimmingCharacters(in: .whitespaces)
        guard h.lowercased().hasPrefix("bytes=") else { return .full }
        let spec = String(h.dropFirst(6)).trimmingCharacters(in: .whitespaces)
        if spec.contains(",") { return .full }
        guard let dash = spec.firstIndex(of: "-") else { return .full }
        let a = String(spec[spec.startIndex..<dash]).trimmingCharacters(in: .whitespaces)
        let b = String(spec[spec.index(after: dash)...]).trimmingCharacters(in: .whitespaces)
        if a.isEmpty {                                  // suffix: the last N bytes
            guard let n = Int64(b), n >= 0 else { return .full }
            if n == 0 || length == 0 { return .unsatisfiable }
            return .partial(start: max(0, length - n), end: length - 1)
        }
        guard let start = Int64(a), start >= 0 else { return .full }
        let end: Int64
        if b.isEmpty { end = Int64.max } else { guard let e = Int64(b) else { return .full }; end = e }
        if end < start { return .full }
        if start >= length { return .unsatisfiable }
        return .partial(start: start, end: min(end, length - 1))
    }
}
