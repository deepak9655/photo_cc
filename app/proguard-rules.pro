# The app has no reflection and no serialization framework, so R8 needs almost
# no guidance. Keep line numbers so any crash report stays readable, and keep the
# classes the framework instantiates by name.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.strata.editor.ui.MainActivity { *; }
