# Mantener modelos de dominio para Gson
-keep class com.digitador.avicola.domain.** { *; }
-keep class com.digitador.avicola.data.** { *; }

# Apache POI
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn com.microsoft.schemas.**
-dontwarn schemaorg_apache_xmlbeans.**
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }

# Mantener clases para XML
-keep class javax.xml.stream.** { *; }
-keep class com.sun.xml.** { *; }
-keep class org.w3c.dom.** { *; }
-keep class org.xml.sax.** { *; }

# Hilt
-keepnames class * extends androidx.lifecycle.ViewModel

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
