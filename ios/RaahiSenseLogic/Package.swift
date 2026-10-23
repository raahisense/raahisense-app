// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "RaahiSenseLogic",
    products: [
        .library(name: "RaahiSenseLogic", targets: ["RaahiSenseLogic"]),
    ],
    targets: [
        .target(name: "RaahiSenseLogic"),
        .testTarget(name: "RaahiSenseLogicTests", dependencies: ["RaahiSenseLogic"]),
    ]
)
