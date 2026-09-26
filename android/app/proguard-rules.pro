# Glance creates widget action callbacks by class name.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
-keep class io.github.xxcay.clipboard.widget.** { *; }

# OkHttp / Okio ship their own rules; silence optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
