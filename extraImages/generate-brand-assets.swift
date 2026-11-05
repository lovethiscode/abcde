// Run with `swift extraImages/generate-brand-assets.swift` on macOS.
// AppIcon-1024.png is the only source; generated assets never redraw the approved art.
import AppKit

let root = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
    .deletingLastPathComponent().deletingLastPathComponent()
let source = root.appendingPathComponent("ios/app-store-assets/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png")
guard let artwork = NSImage(contentsOf: source) else { fatalError("Cannot read \(source.path)") }
let background = NSColor(srgbRed: 34/255, green: 36/255, blue: 60/255, alpha: 1)
var exported = 0

func raster(width: Int, height: Int, transparent: Bool = false,
            circle: Bool = false, contentScale: CGFloat = 1,
            alignment: CGFloat = 0.5) -> NSBitmapImageRep {
    let context = CGContext(data: nil, width: width, height: height,
        bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: transparent ? CGImageAlphaInfo.premultipliedLast.rawValue
            : CGImageAlphaInfo.noneSkipLast.rawValue)!
    context.translateBy(x: 0, y: CGFloat(height))
    context.scaleBy(x: 1, y: -1)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: true)
    let bounds = CGRect(x: 0, y: 0, width: width, height: height)
    if !transparent { context.setFillColor(background.cgColor); context.fill(bounds) }
    if circle { context.addEllipse(in: bounds); context.clip() }
    let side = CGFloat(min(width, height)) * contentScale
    let imageRect = CGRect(x: (CGFloat(width) - side) * alignment,
        y: (CGFloat(height) - side) / 2, width: side, height: side)
    artwork.draw(in: imageRect, from: .zero, operation: .sourceOver, fraction: 1,
        respectFlipped: true, hints: [.interpolation: NSImageInterpolation.high])
    NSGraphicsContext.restoreGraphicsState()
    return NSBitmapImageRep(cgImage: context.makeImage()!)
}

func save(_ path: String, width: Int, height: Int? = nil, transparent: Bool = false,
          circle: Bool = false, contentScale: CGFloat = 1, alignment: CGFloat = 0.5) throws {
    let bitmap = raster(width: width, height: height ?? width, transparent: transparent,
        circle: circle, contentScale: contentScale, alignment: alignment)
    let jpeg = path.lowercased().hasSuffix(".jpg")
    let data = bitmap.representation(using: jpeg ? .jpeg : .png,
        properties: jpeg ? [.compressionFactor: 0.95] : [:])!
    try data.write(to: root.appendingPathComponent(path))
    exported += 1
}

func little16(_ value: UInt16) -> Data {
    var value = value.littleEndian
    return withUnsafeBytes(of: &value) { Data($0) }
}

func little32(_ value: UInt32) -> Data {
    var value = value.littleEndian
    return withUnsafeBytes(of: &value) { Data($0) }
}

// Windows ICO entries carry lossless PNGs, including a real image at every advertised size.
func saveICO(_ path: String, sizes: [Int]) throws {
    let images = sizes.map { size in
        raster(width: size, height: size, transparent: true)
            .representation(using: .png, properties: [:])!
    }
    var data = little16(0) + little16(1) + little16(UInt16(sizes.count))
    var offset = 6 + 16 * sizes.count
    for (size, image) in zip(sizes, images) {
        data.append(contentsOf: [UInt8(size == 256 ? 0 : size), UInt8(size == 256 ? 0 : size), 0, 0])
        data.append(little16(1))
        data.append(little16(32))
        data.append(little32(UInt32(image.count)))
        data.append(little32(UInt32(offset)))
        offset += image.count
    }
    for image in images { data.append(image) }
    try data.write(to: root.appendingPathComponent(path))
    exported += 1
}

for (density, legacy, adaptive) in [("mdpi", 48, 108), ("hdpi", 72, 162),
        ("xhdpi", 96, 216), ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432)] {
    let path = "android/res/mipmap-\(density)"
    try save("\(path)/uncivicon.png", width: legacy)
    try save("\(path)/uncivicon_round.png", width: legacy, transparent: true, circle: true)
    // 108dp layer with the complete square artwork centered in the 66dp content region.
    for name in ["uncivicon_fore", "uncivicon_foreground"] {
        try save("\(path)/\(name).png", width: adaptive,
            transparent: true, contentScale: 66.0 / 108.0)
    }
}

for path in ["android/ic_launcher-web.png", "android/uncivicon-playstore.png",
             "fastlane/metadata/android/en-US/images/icon.png",
             "extraImages/Icons/Unciv icon v6.png", "docs/assets/Icon.png", ".idea/icon.png"] {
    try save(path, width: 512)
}
try save("android/assets/ExtraImages/Icons/AppIcon.png", width: 256)
try save("android/assets/ExtraImages/Icons/Unciv128.png", width: 128)
try save("android/assets/ExtraImages/Icons/Unciv32.png", width: 32)
try save("docs/assets/favicon.png", width: 16)

// Wide surfaces contain the complete approved square; never stretch or crop its subjects.
try save("android/assets/ExtraImages/banner.png", width: 360, height: 180)
try save("android/res/drawable-xhdpi/banner.png", width: 320, height: 180)
try save("extraImages/GithubPreviewImage.jpg", width: 1920, height: 1080)
try save("extraImages/Steam/store_page_background.png", width: 1438, height: 810)
try save("extraImages/Steam/library_logo_transparent.png", width: 640, height: 360, transparent: true)
try save("extraImages/Steam/library_logo_transparent_left.png", width: 640, height: 360,
    transparent: true, alignment: 0)
try save("extraImages/Steam/client_icon_256.png", width: 256)
try save("extraImages/Steam/community_icon.jpg", width: 184)
try saveICO("extraImages/Icons/Unciv.ico", sizes: [16, 32, 48, 64, 128, 256])
for size in [16, 32, 64, 256] {
    try saveICO("extraImages/Steam/client_icon_\(size).ico", sizes: [size])
}
print("Exported \(exported) brand assets from \(source.path)")
