// Run on macOS: swift frontend/tests/native-image-system.swift
// Exercises the same ImageIO decoding primitive as the iOS bridge, not an iOS device test.
import Foundation
import ImageIO
let encoded = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
let bytes = Data(base64Encoded: encoded)!
func decode(_ data: Data) -> Bool {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return false }
    let settings = NSMutableDictionary()
    settings.setObject(true, forKey: kCGImageSourceShouldCacheImmediately as NSString)
    settings.setObject(true, forKey: kCGImageSourceShouldCache as NSString)
    guard let image = CGImageSourceCreateImageAtIndex(source, 0, settings as CFDictionary) else { return false }
    return image.width == 1 && image.height == 1
}
precondition(decode(bytes), "Complete image must decode")
precondition(!decode(bytes.prefix(33)), "Header-only image must not decode")
print("ImageIO complete PNG accepted; 33-byte PNG rejected")
