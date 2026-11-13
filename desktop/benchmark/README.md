# Diff rendering probe

Run from `desktop/`:

```bash
./gradlew :benchmark:diffRenderProbe
```

The probe renders synthetic diffs on an offscreen CPU canvas at 1440 by 900 pixels and density 1. It reads no repository or operator data. It measures 120 frames after 60 warmup frames, reports median and p95 draw time, and closes every rendered image.

The scenarios contain one file, 66 files, or 66 files with a 5,000-character line in the last file. Each file has 120 lines. Scrolling stays within the first file so the final scenario exercises the cost of a long loaded line outside the viewport.

Each scenario runs without a shadow, with the original full modal shadow, and with the outer-only shadow behind the same opaque panel. The panel has a 32-pixel margin so the visible shadow also draws. This comparison isolates covered shadow painting on the current diff renderer. It does not reproduce all costs of the previous implementation.

These are diagnostic measurements, not timing assertions or display FPS. They exclude window presentation, GPU rendering, native input dispatch and the operating system compositor. Compare runs on the same machine with similar load. Use the source-run app described in [Desktop QA](../QA.md) to check the actual overlay, selection and comments.

## Recorded comparison

On 2026-11-12, one run on the development machine produced these times in milliseconds. Other builds were active on the host, so treat the numbers as diagnostic rather than a stable performance budget.

| Scenario | Full shadow median | Outer-only median | Full shadow p95 | Outer-only p95 |
| --- | ---: | ---: | ---: | ---: |
| One file | 22.29 | 4.88 | 44.32 | 8.22 |
| 66 files | 21.72 | 5.04 | 37.40 | 16.04 |
| Long offscreen line | 19.84 | 5.48 | 27.32 | 13.03 |
