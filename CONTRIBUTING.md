# Contributing to WeakNet

Thanks for your interest in contributing! This guide covers how to set up the development environment and submit contributions.

## Development Setup

### Prerequisites

- Android Studio (latest stable)
- JDK 11+
- Android device or emulator (VPN service requires a device to test)
- minSdk 23+ target device

### Build

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew compileDebugKotlin     # Compile only (quick syntax check)
./gradlew test                   # Run unit tests
```

### Release Build

Release builds require signing configuration. Add to `local.properties`:

```properties
RELEASE_STORE_FILE=../your_keystore
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_password
```

Debug builds work without any extra configuration.

## How to Contribute

1. **Fork** the repository
2. Create a **feature branch** from `master`: `git checkout -b feature/your-feature`
3. Make your changes
4. Ensure `./gradlew compileDebugKotlin` passes
5. **Commit** with a descriptive message
6. Submit a **Pull Request** to the `master` branch

## Code Style

- **Kotlin official code style** (configured via `kotlin.code.style=official`)
- File header comments: preserve the existing `@author`/`@date`/`@desc` format
- No unnecessary comments — let well-named identifiers speak for themselves
- Follow the existing package structure

## Commit Messages

Use concise, descriptive messages. Examples from the project:

- `Feature：添加 DNS 故障模拟`
- `Fix：修复 TCP 连接超时`
- `Refactor：优化线程池配置`

## Reporting Issues

- Use GitHub Issues
- Include: device model, Android version, app version, steps to reproduce
- For bugs: attach relevant logcat output if possible
- For features: describe the use case and expected behavior

## Areas for Contribution

- New preset scenarios
- Additional delay/loss models
- UI/UX improvements
- Performance optimizations
- Documentation and translations
- Test coverage
