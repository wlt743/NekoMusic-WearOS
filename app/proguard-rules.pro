# kotlinx.serialization：保留 @Serializable 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }

# Ktor / OkHttp 的可选依赖提示
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
