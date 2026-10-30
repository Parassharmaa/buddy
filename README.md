# Buddy

Kotlin Android assistant. Requirements: [spec.md](spec.md).

Build with JDK 27, SDK 37.2, build-tools 37.0.0, NDK 30.0.16248370 and CMake 4.1.2:

```sh
./gradlew lintKotlin :core:test :app:assembleDebug :app:lintDebug
```

Development build; phone control requires device qualification. Screenshots and local device logs belong in ignored `captures/`.
