# Use MapLibre's published AAR consumer rules for native JNI/reflection.
# Do not pin the entire library: R8 can then strip unreachable Java code.
-dontwarn org.maplibre.**
-dontwarn org.locationtech.jts.**

# Room instantiates this generated WorkManager database through reflection at startup.
-keep class androidx.work.impl.WorkDatabase_Impl { public <init>(); }

# Liftoff Monetize/Vungle uses SDK callbacks and OMID reflection.
# Required for release builds with R8 shrinking enabled.
-dontwarn com.vungle.ads.**
-keep class com.vungle.ads.** { *; }
-dontwarn com.iab.omid.library.vungle.**
-keep class com.iab.omid.library.vungle.** { *; }
