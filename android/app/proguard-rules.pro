# App code talks to the bridge with org.json (part of Android) and OkHttp (ships its own
# consumer rules), and uses no reflection, so R8's defaults are enough.
# Keep line numbers so crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
