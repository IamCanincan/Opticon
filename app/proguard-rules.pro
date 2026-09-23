# The entry class is listed by name in META-INF/xposed/java_init.list, it must survive R8.
# Do NOT add allowobfuscation here, and do NOT add -adaptresourcefilecontents for that list:
# META-INF/xposed/ is packed verbatim (AAPT2 doesn't touch it), so the framework reads the
# exact class name we wrote there — rewriting either side breaks the match.
-keep class com.iamcanincan.opticon.entry.OpticonModule
-keep class com.iamcanincan.opticon.entry.OpticonModule {
    public <init>();
}

-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-dontwarn io.github.libxposed.annotation.**
