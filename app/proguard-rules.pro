# LayerPad keeps its rules minimal on purpose: the app has no reflection, no
# serialisation and no third-party code, so R8's defaults are already safe.

# Keep line numbers so a stack trace from a user's phone still means something.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The PSD engine is called only from the UI, so it is fully reachable and R8 is
# free to inline and shrink it. The self-test harness (app/src/test/) is not part
# of the app and never reaches this build.
