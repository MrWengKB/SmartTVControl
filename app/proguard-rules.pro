# OkHttp ships its own rules; kotlinx.serialization is only used for JsonElement (no reflection).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# The pairing manifest is loaded as a classpath resource next to this class.
-keep class com.mrwengkb.weboscontrol.core.Pairing { *; }
