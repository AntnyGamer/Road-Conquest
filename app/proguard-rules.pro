# Use MapLibre's published AAR consumer rules for native JNI/reflection.
# Do not pin the entire library: R8 can then strip unreachable Java code.
-dontwarn org.maplibre.**
-dontwarn org.locationtech.jts.**

# Room instantiates this generated WorkManager database through reflection at startup.
-keep class androidx.work.impl.WorkDatabase_Impl { public <init>(); }
