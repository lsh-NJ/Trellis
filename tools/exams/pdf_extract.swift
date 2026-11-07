#!/usr/bin/env swift
//
// pdf_extract.swift — 用 macOS 原生 PDFKit 抽试卷文本；扫描件自动走 Vision OCR。
//
// 为什么不是 Python：本机没有 poppler/pdftotext，pip 装 PDF 解析库要联网且对中文扫描件
// 没用；PDFKit 与 Vision 是系统自带、离线可用，`VNRecognizeTextRequest` 的 zh-Hans
// 识别质量在本机已验证过。
//
// 用法：
//   swift tools/exams/pdf_extract.swift <input.pdf> <output.jsonl> \
//       [--media-dir DIR] [--source-path REPO_PATH] [--mode auto|text|ocr]
//       [--min-chars 100] [--scale 2.0] [--max-pages N] [--force]
//
// 输出：JSONL，一页一行——
//   {"sourcePath":"public/离散数学/试卷/离散2015-2016-1-B卷.pdf","file":"...","page":1,
//    "method":"text"|"ocr","chars":123,"text":"..."}
//
// 约定：
// - 每页先试 PDF 内嵌文本；去空白后字符数 < --min-chars 视为扫描件，转 OCR；
// - OCR 的页面 PNG 写到 --media-dir（建议放 tools/exams/.cache/media/，**不要提交**）；
// - OCR 结果按 Vision 的 boundingBox 从上到下、从左到右重排（中文试卷常分栏，仍可能串行；
//   已知问题见 tools/exams/README.md）；
// - 输出文件已存在且未加 --force 时直接跳过，便于整目录重复跑。

import Foundation
import AppKit
import PDFKit
import Vision

struct PageRecord: Codable {
    let sourcePath: String
    let file: String
    let page: Int
    let method: String
    let chars: Int
    let text: String
}

struct Options {
    var input: URL
    var output: URL
    var mediaDir: URL?
    var sourcePath: String
    var mode: String = "auto"
    var minChars: Int = 100
    var scale: CGFloat = 2.0
    var maxPages: Int = Int.max
    var force: Bool = false
}

func usage() -> String {
    """
    用法: swift tools/exams/pdf_extract.swift <input.pdf> <output.jsonl> [选项]

    选项:
      --media-dir DIR     OCR 页面的 PNG 输出目录（默认与输出同目录的 media/<stem>/）
      --source-path PATH  记录到 JSONL 的上游仓库路径（默认取输入文件名）
      --mode MODE         auto | text | ocr（默认 auto：先抽文本，太少再 OCR）
      --min-chars N       每页文本少于 N 字视为扫描件（默认 100）
      --scale N           OCR 渲染倍率（默认 2.0）
      --max-pages N       最多处理前 N 页
      --force             输出已存在也重新处理
    """
}

func parseArguments() -> Options? {
    var args = Array(CommandLine.arguments.dropFirst())
    guard args.count >= 2 else { return nil }
    let input = URL(fileURLWithPath: args.removeFirst())
    let output = URL(fileURLWithPath: args.removeFirst())
    var options = Options(
        input: input,
        output: output,
        mediaDir: nil,
        sourcePath: input.lastPathComponent
    )

    while !args.isEmpty {
        let flag = args.removeFirst()
        func value(_ name: String) -> String? {
            guard let next = args.first, !next.hasPrefix("--") else {
                FileHandle.standardError.write("参数 \(name) 缺少值\n".data(using: .utf8)!)
                return nil
            }
            args.removeFirst()
            return next
        }
        switch flag {
        case "--media-dir":
            guard let v = value(flag) else { return nil }
            options.mediaDir = URL(fileURLWithPath: v)
        case "--source-path":
            guard let v = value(flag) else { return nil }
            options.sourcePath = v
        case "--mode":
            guard let v = value(flag), ["auto", "text", "ocr"].contains(v) else { return nil }
            options.mode = v
        case "--min-chars":
            guard let v = value(flag), let n = Int(v) else { return nil }
            options.minChars = n
        case "--scale":
            guard let v = value(flag), let n = Double(v) else { return nil }
            options.scale = CGFloat(n)
        case "--max-pages":
            guard let v = value(flag), let n = Int(v) else { return nil }
            options.maxPages = n
        case "--force":
            options.force = true
        case "--help", "-h":
            print(usage())
            exit(0)
        default:
            FileHandle.standardError.write("未知参数 \(flag)\n".data(using: .utf8)!)
            return nil
        }
    }
    return options
}

/// 把一页 PDF 渲染成位图。扫描件的内嵌文本为空，只能靠 OCR。
func renderPage(_ page: PDFPage, scale: CGFloat) -> CGImage? {
    let bounds = page.bounds(for: .mediaBox)
    let width = max(1, Int(bounds.width * scale))
    let height = max(1, Int(bounds.height * scale))
    guard let context = CGContext(
        data: nil,
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }
    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
    context.scaleBy(x: scale, y: scale)
    page.draw(with: .mediaBox, to: context)
    return context.makeImage()
}

/// 扫描件 OCR。中文 + 英文按 Vision 的 boundingBox 重排成阅读顺序。
func ocr(_ image: CGImage) -> String {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.recognitionLanguages = ["zh-Hans", "en-US"]
    request.usesLanguageCorrection = true
    request.minimumTextHeight = 0.005

    let handler = VNImageRequestHandler(cgImage: image, options: [:])
    do {
        try handler.perform([request])
    } catch {
        return ""
    }
    let observations = request.results ?? []
    // Vision 坐标原点在左下角：按上 -> 下（minY 降序）、左 -> 右（minX 升序）排序。
    let sorted = observations.sorted { lhs, rhs in
        let dy = lhs.boundingBox.midY - rhs.boundingBox.midY
        if abs(dy) > 0.01 { return dy > 0 }
        return lhs.boundingBox.minX < rhs.boundingBox.minX
    }
    let lines = sorted.compactMap { $0.topCandidates(1).first?.string }
    return lines.joined(separator: "\n")
}

func writePng(_ image: CGImage, to url: URL) {
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) else {
        return
    }
    CGImageDestinationAddImage(destination, image, nil)
    CGImageDestinationFinalize(destination)
}

func visibleCharCount(_ text: String) -> Int {
    text.trimmingCharacters(in: .whitespacesAndNewlines).count
}

func main() -> Int32 {
    guard let options = parseArguments() else {
        FileHandle.standardError.write((usage() + "\n").data(using: .utf8)!)
        return 2
    }

    guard FileManager.default.fileExists(atPath: options.input.path) else {
        FileHandle.standardError.write("找不到输入 PDF：\(options.input.path)\n".data(using: .utf8)!)
        return 2
    }
    if FileManager.default.fileExists(atPath: options.output.path) && !options.force {
        print("跳过（输出已存在，加 --force 重跑）：\(options.output.path)")
        return 0
    }
    guard let document = PDFDocument(url: options.input) else {
        FileHandle.standardError.write("PDF 打不开：\(options.input.path)\n".data(using: .utf8)!)
        return 1
    }
    guard document.pageCount > 0 else {
        FileHandle.standardError.write("PDF 没有页面：\(options.input.path)\n".data(using: .utf8)!)
        return 1
    }

    let mediaDir = options.mediaDir ?? options.output.deletingLastPathComponent()
        .appendingPathComponent("media")
        .appendingPathComponent(options.input.deletingPathExtension().lastPathComponent)
    try? FileManager.default.createDirectory(at: mediaDir, withIntermediateDirectories: true)

    let pageCount = min(document.pageCount, options.maxPages)
    var records: [PageRecord] = []
    var textPages = 0
    var ocrPages = 0

    for index in 0..<pageCount {
        guard let page = document.page(at: index) else { continue }
        let pageNumber = index + 1
        var method = "text"
        var text = page.string ?? ""

        let needOcr: Bool
        switch options.mode {
        case "ocr": needOcr = true
        case "text": needOcr = false
        default: needOcr = visibleCharCount(text) < options.minChars
        }

        if needOcr {
            method = "ocr"
            if let image = renderPage(page, scale: options.scale) {
                let png = mediaDir.appendingPathComponent(
                    String(format: "%@-p%03d.png", options.input.deletingPathExtension().lastPathComponent, pageNumber)
                )
                writePng(image, to: png)
                text = ocr(image)
            }
        }

        if method == "text" { textPages += 1 } else { ocrPages += 1 }
        let record = PageRecord(
            sourcePath: options.sourcePath,
            file: options.input.lastPathComponent,
            page: pageNumber,
            method: method,
            chars: visibleCharCount(text),
            text: text
        )
        records.append(record)
        print(String(format: "  p%03d %@ %d 字", pageNumber, method, record.chars), terminator: "\n")
    }

    let encoder = JSONEncoder()
    encoder.outputFormatting = []
    var lines: [String] = []
    for record in records {
        guard let data = try? encoder.encode(record),
              let line = String(data: data, encoding: .utf8) else { continue }
        lines.append(line)
    }
    let payload = lines.joined(separator: "\n") + (lines.isEmpty ? "" : "\n")
    do {
        try payload.write(to: options.output, atomically: true, encoding: .utf8)
    } catch {
        FileHandle.standardError.write("写输出失败：\(error)\n".data(using: .utf8)!)
        return 1
    }

    print("完成：\(records.count) 页（文本 \(textPages) / OCR \(ocrPages)）-> \(options.output.path)")
    return ocrPages > 0 && records.allSatisfy({ $0.chars == 0 }) ? 1 : 0
}

exit(main())
