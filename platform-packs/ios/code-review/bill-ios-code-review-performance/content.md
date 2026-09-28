---
name: bill-ios-code-review-performance
description: Use when reviewing measurable iOS rendering, memory, I/O, and resource risks.
internal-for: skill-bill
---

# Performance Review Specialist

Review only plausible production performance failures.

## Focus

- SwiftUI recomputation and rendering cost
- ImageIO and media memory behavior
- Bounded concurrency, I/O, and resource lifetime

## Ignore

- Unmeasured micro-optimizations on cold paths
- Style preferences without latency, memory, or energy risk

## Applicability

Apply rules to detected SwiftUI/UIKit, media, import, networking, or persistence hot paths. Calibrate findings to device class, supported OS versions, workload size, and measurement evidence.

## Project-Specific Rules

### Rendering Performance Rules

- Reject expensive work repeated from SwiftUI `body`; sorting, decoding, or I/O on every recomputation causes frame latency and visible interaction failures.
- Collection views must provide stable `ForEach` or `UICollectionViewDiffableDataSource` identity; reject index identity that invalidates all rows and causes incorrect animation state.
- Animation work must limit layout and offscreen rendering cost; reject unbounded `.drawingGroup()` or repeated geometry reads that create GPU or memory regressions.
- Main-actor callbacks must move heavy parsing or image processing off `@MainActor`; reject synchronous work that blocks lifecycle events and produces dropped frames.
- Image loads must downsample with `CGImageSourceCreateThumbnailAtIndex` before materializing full pixels; reject `UIImage(contentsOfFile:)` for thumbnails because memory pressure can terminate the app.
- Reusable UIKit cells must cancel and reset image work in `prepareForReuse()`; reject stale completion races that render incorrect content and waste resources.

### Resource And Throughput Rules

- Parallel task creation must use bounded `withTaskGroup` scheduling for large inputs; reject one `Task` per item when it risks resource starvation or timeouts.
- Large Objective-C bridging loops must use measured, bounded `autoreleasepool` scopes; reject temporary-object accumulation that causes memory failure.
- File and security-scoped URL access must balance `startAccessingSecurityScopedResource()` with cleanup; reject leaked handles that exhaust process resources.
- Database imports must batch transactions and indexed lookups through detected `NSBatchInsertRequest`, SwiftData, or SQLite tooling; reject O(n²) access that creates operational latency.
- Network media streams must write incrementally or enforce a size bound instead of accumulating `Data`; reject unbounded buffers that crash under memory pressure.
- Performance changes must include `XCTMetric`, Instruments, signpost, or equivalent observable evidence when the regression threshold is disputed; reject claims that conceal a reproducible latency failure.
- For Blocker or Major findings, describe the concrete latency, memory-pressure, or throughput failure scenario.
