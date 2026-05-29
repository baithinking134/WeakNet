# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] - 2026-05-29

### Added

- 12 preset weak network scenarios (Unlimited, 2G, 3G, 4G weak, WiFi weak, Subway, Elevator, High latency, Packet loss hell, High-speed rail, DNS timeout, Data corruption)
- 5 packet manipulations: Throttle, Reorder, Duplicate, Loss, Tamper (pipeline-chained)
- 3 delay distributions: Uniform, Gaussian (Box-Muller), Long-tail (Log-normal)
- 2 loss models: Random (Bernoulli), Burst (Gilbert two-state Markov chain)
- Network disconnect simulation with periodic disconnect/reconnect and automatic RST cleanup
- DNS fault simulation: Timeout (silent drop), Failure (SERVFAIL), Hijack (forged A record)
- Per-app filtering — select specific apps to affect
- Floating window monitor with real-time speed, session count, pause/stop controls
- Custom configuration with save/load support
- Full IP protocol coverage: TCP, UDP, ICMP (Ping)
- User-space TCP state machine with proper session lifecycle management
- IP fragment reassembly
- Detailed architecture documentation
