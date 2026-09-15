# Consumer proguard rules for aurora-transport-sabr
# Keep SABR transport classes that are registered dynamically
-keep class com.aurora.engine.transport.sabr.SabrPlaybackTransport { *; }
