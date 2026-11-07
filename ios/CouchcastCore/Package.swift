// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CouchcastCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "CouchcastCore", targets: ["CouchcastCore"])],
    targets: [
        .target(name: "CouchcastCore", swiftSettings: [.swiftLanguageVersion(.v5)]),
        .testTarget(name: "CouchcastCoreTests", dependencies: ["CouchcastCore"], swiftSettings: [.swiftLanguageVersion(.v5)]),
    ]
)
