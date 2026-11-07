// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CouchcastCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "CouchcastCore", targets: ["CouchcastCore"])],
    targets: [
        .target(name: "CouchcastCore"),
        .testTarget(name: "CouchcastCoreTests", dependencies: ["CouchcastCore"]),
    ],
    swiftLanguageVersions: [.v5]
)
