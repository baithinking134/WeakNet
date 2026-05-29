# === Crash reports ===
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# === Enums used in serialization ===
# DnsFaultType: serialized via .name() / valueOf() in ProfileRepository JSON and rememberSaveable
-keep class com.awu.weaknet.data.model.DnsFaultType { *; }
# DelayModel / LossModel: .name used in logs and StateFlow
-keep class com.awu.weaknet.data.model.DelayModel { *; }
-keep class com.awu.weaknet.data.model.LossModel { *; }
# TcpState: internal VPN state, keep for stack trace clarity
-keep class com.awu.weaknet.vpn.nat.TcpState { *; }

# === Kotlin singletons ===
-keep class com.awu.weaknet.vpn.VpnConfig { *; }
-keep class com.awu.weaknet.service.VpnStateHolder { *; }
-keep class com.awu.weaknet.vpn.engine.DnsResponder { *; }
-keep class com.awu.weaknet.util.ByteUtils { *; }
