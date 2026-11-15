# Android perf notes (Task 7.1)

| build | APK | size |
|---|---|---|
| run 44 (R8 full, shrinkResources, ABI splits) | Cinetor-0.1.44-arm64-v8a.apk | 16.8 MB |
| | Cinetor-0.1.44-armeabi-v7a.apk | 16.8 MB |
| | Cinetor-0.1.44-x86_64.apk | 16.8 MB |
| | Cinetor-0.1.44-universal.apk | 16.9 MB |
| | debug universal (no R8) | 37.9 MB |

- Bundled mock catalog (547 files, mostly webp art) ≈ 15 MB of every APK; native code (Compose graphics-path) is tiny,
  so ABI splits save little. Real-provider builds without mock art would be ≈ 3 MB.
- Baseline profile: hand-written `app/src/main/baseline-prof.txt` (startup, home, details, player, TV UI, hot libs) +
  profileinstaller; bundled as `assets/dexopt/baseline.prof` (CI asserts).
- Compose compiler stability reports/metrics: CI artifact `r8-mapping-and-compose-reports`.
- Macrobenchmark (cold start < 1.5 s, jank < 5 %, LOW memory < 200 MB): NOT run yet (no benchmark module) → Phase 10.
