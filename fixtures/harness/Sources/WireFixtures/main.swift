import Foundation

// Entry point for `swift run wire-fixtures <out-dir>`.
// The Crypto Vectors and Session Message Samples tasks add the generators.

let arguments = CommandLine.arguments
guard arguments.count == 2 else {
    let stderr = FileHandle.standardError
    stderr.write(Data("usage: wire-fixtures <out-dir>\n".utf8))
    exit(64)
}

let outputDirectory = URL(fileURLWithPath: arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
print("wire-fixtures: no generators yet; \(outputDirectory.path) left empty")
