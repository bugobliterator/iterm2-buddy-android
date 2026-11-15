// swift-tools-version: 5.9

import PackageDescription

// Compiles iTerm2 v3.7.7's own Swift sources so the wire-fixture generator
// emits exactly what the Mac and the iOS companion produce. Run
// ./sync-upstream.sh first; it fills Sources/*/Upstream from upstream-files.txt.
let package = Package(
    name: "WireFixturesHarness",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "wire-fixtures", targets: ["WireFixtures"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"5.0.0")
    ],
    targets: [
        .target(
            name: "CompanionProtocol",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto")
            ],
            path: "Sources/CompanionProtocol"
        ),
        // CompanionMessages.swift declares its message enums `internal`, so the
        // generator compiles in the same module as the upstream message sources.
        .executableTarget(
            name: "WireFixtures",
            dependencies: [
                "CompanionProtocol",
                .product(name: "Crypto", package: "swift-crypto")
            ],
            path: "Sources/WireFixtures"
        )
    ]
)
