// Run with `swift ios/app-store-assets/generate-launch-artwork.swift` from the public repository.
// Both UIKit and the pre-skin game startup screen use these exact 3x assets.
import AppKit
import zlib

let root = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
let output = root.appendingPathComponent("android/assets/ExtraImages")
let blue = NSColor(srgbRed: 23/255, green: 52/255, blue: 63/255, alpha: 1)
let gold = NSColor(srgbRed: 226/255, green: 200/255, blue: 147/255, alpha: 1)
let text = NSColor(srgbRed: 243/255, green: 243/255, blue: 234/255, alpha: 1)
let muted = NSColor(srgbRed: 189/255, green: 208/255, blue: 212/255, alpha: 1)

// AppKit's PNG writer reduces the profile to an sRGB hint. Embed the actual ICC data so
// UIKit launch snapshots and the game's raw texture pixels agree on wide-colour displays.
func encodePNG(_ bitmap: NSBitmapImageRep) -> Data {
    let png = bitmap.representation(using: .png, properties: [:])!
    let profile = NSColorSpace.sRGB.iccProfileData!
    var compressed = [UInt8](repeating: 0, count: Int(compressBound(uLong(profile.count))))
    var length = uLongf(compressed.count)
    let status = profile.withUnsafeBytes { source in
        compressed.withUnsafeMutableBufferPointer { destination in
            compress2(destination.baseAddress, &length, source.bindMemory(to: UInt8.self).baseAddress,
                uLong(profile.count), Z_BEST_COMPRESSION)
        }
    }
    precondition(status == Z_OK)
    func word(_ value: UInt32) -> Data {
        var bigEndian = value.bigEndian
        return withUnsafeBytes(of: &bigEndian) { Data($0) }
    }
    let payload = Data("sRGB\0\0".utf8) + Data(compressed.prefix(Int(length)))
    let body = Data("iCCP".utf8) + payload
    let crc = body.withUnsafeBytes { crc32(0, $0.bindMemory(to: UInt8.self).baseAddress, uInt(body.count)) }
    let chunk = word(UInt32(payload.count)) + body + word(UInt32(crc))
    var result = Data(png.prefix(8))
    var offset = 8
    while offset < png.count {
        let count = png[offset..<offset+4].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        let end = offset + Int(count) + 12
        let type = String(data: png[offset+4..<offset+8], encoding: .ascii)!
        if type != "sRGB" { result.append(png[offset..<end]) }
        if type == "IHDR" { result.append(chunk) }
        offset = end
    }
    return result
}

func render(_ name: String, width: Int, height: Int, draw: (CGContext) -> Void) throws {
    let context = CGContext(data: nil, width: width * 3, height: height * 3,
        bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.translateBy(x: 0, y: CGFloat(height * 3))
    context.scaleBy(x: 3, y: -3)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: true)
    draw(context)
    NSGraphicsContext.restoreGraphicsState()
    let bitmap = NSBitmapImageRep(cgImage: context.makeImage()!)
    let png = encodePNG(bitmap)
    try png.write(to: output.appendingPathComponent(name + "@3x.png"))
}

func hexagon(_ rect: CGRect) -> CGPath {
    let path = CGMutablePath()
    let points: [(CGFloat, CGFloat)] = [(0.5,0),(0.96,0.25),(0.96,0.75),(0.5,1),(0.04,0.75),(0.04,0.25)]
    path.move(to: CGPoint(x: rect.minX + points[0].0 * rect.width, y: rect.minY))
    for (x,y) in points.dropFirst() {
        path.addLine(to: CGPoint(x: rect.minX + x * rect.width, y: rect.minY + y * rect.height))
    }
    path.closeSubpath()
    return path
}

try render("LaunchMark", width: 252, height: 214) { context in
    context.setStrokeColor(gold.cgColor)
    context.setLineWidth(2)
    context.addPath(hexagon(CGRect(x: 79, y: 1, width: 94, height: 103)))
    context.strokePath()
    context.setLineJoin(.round)
    context.setLineCap(.round)
    context.saveGState()
    context.translateBy(x: 101, y: 33)
    context.scaleBy(x: 50/24, y: 50/24)
    context.setLineWidth(1.15)
    context.move(to: CGPoint(x: 8, y: 3))
    for point in [CGPoint(x:12,y:11), CGPoint(x:17,y:6), CGPoint(x:22,y:21), CGPoint(x:2,y:21)] {
        context.addLine(to: point)
    }
    context.closePath()
    context.strokePath()
    context.restoreGState()
    context.setLineWidth(1.4)
    context.move(to: CGPoint(x: 124, y: 46))
    context.addLine(to: CGPoint(x: 124, y: 20))
    context.addLine(to: CGPoint(x: 141, y: 28))
    context.addLine(to: CGPoint(x: 124, y: 36))
    context.strokePath()
    let paragraph = NSMutableParagraphStyle()
    paragraph.alignment = .center
    NSAttributedString(string: "Unciv4iOS", attributes: [.font: NSFont(name: "Georgia", size: 42)!,
        .foregroundColor: text, .kern: 1.47, .paragraphStyle: paragraph])
        .draw(in: CGRect(x: 0, y: 125, width: 252, height: 49))
    NSAttributedString(string: "文明，由你书写", attributes: [.font: NSFont(name: "PingFangSC-Regular", size: 14)!,
        .foregroundColor: muted, .kern: 4.2, .paragraphStyle: paragraph])
        .draw(in: CGRect(x: 0, y: 192, width: 252, height: 22))
}

try render("LaunchBackground", width: 1024, height: 512) { context in
    context.setFillColor(blue.cgColor)
    context.fill(CGRect(x: 0, y: 0, width: 1024, height: 512))
    let horizon = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: [
        blue.cgColor, NSColor(srgbRed: 28/255, green: 60/255, blue: 71/255, alpha: 1).cgColor
    ] as CFArray, locations: [0,1])!
    context.drawLinearGradient(horizon, start: CGPoint(x:0,y:369), end: CGPoint(x:0,y:512), options: [])
    context.saveGState()
    context.setStrokeColor(muted.withAlphaComponent(0.22).cgColor)
    context.setLineWidth(1)
    context.translateBy(x: 1050, y: 0)
    context.rotate(by: -.pi * 22/180)
    for inset in stride(from: CGFloat(30), through: 138, by: 36) {
        let size = 540 - inset * 2
        let path = CGMutablePath()
        path.move(to: CGPoint(x: -270+inset, y: 0))
        path.addCurve(to: CGPoint(x:0,y:-size/2), control1: CGPoint(x:-size/2,y:-size/2), control2: CGPoint(x:-size/4,y:-size/2-20))
        path.addCurve(to: CGPoint(x:size/2,y:0), control1: CGPoint(x:size/2,y:-size/2), control2: CGPoint(x:size/2+12,y:-size/4))
        path.addCurve(to: CGPoint(x:0,y:size/2), control1: CGPoint(x:size/2,y:size/2), control2: CGPoint(x:size/4,y:size/2+20))
        path.addCurve(to: CGPoint(x:-size/2,y:0), control1: CGPoint(x:-size/2,y:size/2), control2: CGPoint(x:-size/2-12,y:size/4))
        context.addPath(path)
        context.strokePath()
    }
    context.restoreGState()
    context.translateBy(x: -30, y: 322)
    context.rotate(by: -.pi * 12/180)
    for row in 0..<6 {
        for column in 0..<6 {
            let opacity = max(0, 0.22 * (1 - CGFloat(column + 5 - row)/7))
            context.setFillColor(NSColor(srgbRed: 45/255, green: 84/255, blue: 97/255, alpha: opacity).cgColor)
            context.addPath(hexagon(CGRect(x: column*56 + (row%2)*28, y: row*47, width: 52, height: 59)))
            context.fillPath()
        }
    }
}
