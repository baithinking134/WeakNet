English | [中文](README.md)

<div align="center">

# WeakNet

**Android Weak Network Simulator**

Intercepts all IP traffic via `VpnService` in user space, applying delay, packet loss, throttle, duplicate, reorder, and tamper manipulations.<br>
Supports network disconnect and DNS fault simulation. No root required, no proxy configuration needed — install and use.

<p>

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android)
![Compose](https://img.shields.io/badge/Jetpack_Compose-BOM%202024.09-4285F4?logo=jetpackcompose)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

</p>

</div>

---

## Why WeakNet

Mobile app behavior under poor network conditions directly impacts user experience — timeouts, blurry images, data loss, duplicate requests. Developers need **reproducible weak network environments** to verify app resilience.

**WeakNet** intercepts **all IP traffic** (TCP/UDP/ICMP) at the packet level via Android `VpnService`. No root, not limited to HTTP, 12 real-world preset scenarios included.

## Features

- **Full protocol coverage** — TCP, UDP, ICMP (Ping), not limited to HTTP
- **12 preset scenarios** — 2G/3G/4G weak signal/WiFi weak/Subway/Elevator/High-speed rail/High latency/Packet loss hell/DNS timeout/Data corruption
- **5 packet manipulations** — Throttle, Reorder, Duplicate, Loss, Tamper, combined in a pipeline
- **3 delay distributions** — Uniform, Gaussian (Box-Muller), Long-tail (Log-normal)
- **2 loss models** — Random (Bernoulli), Burst (Gilbert Markov chain)
- **Network disconnect** — Periodic disconnect/reconnect simulation with automatic RST cleanup on recovery
- **DNS fault simulation** — Timeout (silent drop) / Failure (SERVFAIL) / Hijack (forged A record)
- **Per-app filtering** — Select specific apps to affect without impacting others
- **Floating window monitor** — Real-time upload/download speed, session count, pause/stop controls
- **Custom configuration** — All parameters adjustable, save as custom profiles

## Getting Started

### Prerequisites

- Android Studio
- Android device or emulator (VPN service cannot be tested on host machine)
- minSdk 23+

### Build

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew compileDebugKotlin     # Compile only (quick check)
./gradlew test                   # Run unit tests
```

### Release Build

Add signing configuration to `local.properties` (not tracked by git):

```properties
RELEASE_STORE_FILE=../your_keystore
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_password
```

## Presets

| Preset | Delay | Loss | Throttle | Special |
|:-------|:-----:|:----:|:--------:|:--------|
| Unlimited | — | — | — | — |
| 2G (EDGE) | 300ms | 5% | 50kbps | — |
| 3G (HSPA) | 100ms | 2% | 750kbps | — |
| 4G Weak Signal | 50ms | 1% | 3Mbps | — |
| WiFi Weak | 30ms | — | 5Mbps | Jitter 15ms |
| Subway | 200ms | 8% | 1Mbps | Disconnect 30s/2s |
| Elevator | 500ms | 15% | 100kbps | Disconnect 20s/3s |
| High Latency | 2000ms | — | — | Jitter 500ms |
| Packet Loss Hell | 100ms | 30% | — | Duplicate 5% + Reorder |
| High-speed Rail | 150ms | 5% | 800kbps | Disconnect 60s/4s |
| DNS Timeout | — | — | — | DNS queries dropped |
| Data Corruption | — | — | — | Tamper 10% |

## Architecture

```
App traffic → TUN interface (VpnService)
    → PacketReader (blocking read from TUN fd)
    → PacketProcessor (parse IP/TCP/UDP, TCP state machine, DNS intercept, disconnect guard)
        → ManipulationPipeline (Throttle → Reorder → Duplicate → Loss → Tamper)
        → protect()'d SocketChannel / DatagramChannel → Real network
```

WeakNet operates as a **user-space TCP/IP proxy**: App → TUN → VPN (parse + manipulate + forward) → protect()'d Socket → Real network. TCP connections are established by the VPN on behalf of the app; the app only communicates with the VPN's virtual TCP endpoint.

<details>
<summary><b>TCP Proxy Model</b></summary>

The VPN proxy model involves **two TCP connections**:

```
App ←── TCP ──→ VPN (virtual endpoint) ←── TCP ──→ Real server
     VPN ACKs internally              VPN connects to real server
```

This means:
- **Outgoing**: VPN ACKs data from the app immediately, then forwards upstream. If the pipeline drops the packet, data is already ACKed and unrecoverable → TCP loss is determined **before** ACK (no ACK = app TCP retransmits naturally)
- **Incoming**: VPN reads data from upstream socket (kernel already ACKed), then constructs TCP packets to write to TUN. If the pipeline drops/buffers/tampers, data is permanently lost → incoming TCP skips Reorder/Loss/Tamper

</details>

<details>
<summary><b>Components</b></summary>

| Component | Responsibility |
|-----------|---------------|
| `WeakNetVpnService` | VpnService implementation, establishes TUN interface |
| `VpnThread` | Orchestrator, coordinates TUN read/write, UDP polling, stats, session cleanup |
| `PacketProcessor` | Core: IP/TCP/UDP parsing, TCP state machine, DNS interception |
| `ManipulationPipeline` | Chains 5 manipulators (`flatMap` for 1→N packet transformation) |
| `DisconnectScheduler` | Disconnect timer, modulo-based periodic state switching |
| `DnsResponder` | DNS response construction (SERVFAIL / hijack IP) |

</details>

<details>
<summary><b>Thread Model</b></summary>

| Thread Pool | Config | Purpose |
|-------------|--------|---------|
| `connectExecutor` | CachedThreadPool (on-demand) | TCP connection establishment + relayUpstream blocking loop |
| `outgoingExecutor` | FixedThreadPool(4) | TCP outgoing pipeline (per-session writeLock ordering) |
| `udpExecutor` | CachedThreadPool (on-demand) | UDP outgoing/incoming pipeline |
| `icmpExecutor` | SingleThreadExecutor | ICMP Echo Reply |
| `dnsExecutor` | SingleThreadExecutor | DNS fault responses |

</details>

<details>
<summary><b>Project Structure</b></summary>

```
app/src/main/java/com/awu/weaknet/
├── vpn/                          # VPN core
│   ├── WeakNetVpnService.kt      # VpnService implementation
│   ├── VpnThread.kt              # Orchestrator
│   ├── VpnConfig.kt              # Constants
│   ├── packet/                   # IP/TCP/UDP header parsing, IP fragment reassembly
│   ├── nat/                      # TCP session state machine, UDP session manager
│   ├── engine/                   # PacketReader, PacketProcessor, UpstreamReader, DnsResponder
│   └── manipulation/             # 5 manipulators + Pipeline + DisconnectScheduler
├── data/
│   ├── model/                    # NetworkCondition, NetworkProfile, TrafficStats, AppInfo
│   └── repository/               # ProfileRepository (DataStore), AppRepository
├── service/                      # VpnStateHolder (global state), FloatingWindowService
└── ui/screen/                    # HomeScreen, ProfileListScreen, ProfileEditScreen, AppSelectorScreen
```

</details>

See [docs/architecture.md](docs/architecture.md) for detailed architecture documentation.

## Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose (BOM 2024.09.00) + Material 3 |
| Build | AGP 8.13.2, Gradle 8.13 |
| Target | compileSdk / targetSdk 36, minSdk 23 |
| Navigation | Navigation Compose |
| Persistence | DataStore Preferences |
| Architecture | MVVM + Singleton StateFlow |
