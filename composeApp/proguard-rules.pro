# R8 rules for the release build, as in Unitool: almost empty on purpose.
#
# Compose, androidx, kotlinx.serialization, Ktor and QuickJS ship their own consumer rules inside
# their artifacts, so R8 already knows what they reach by name. A rule added below should say which
# class is reached reflectively and by what, or it is a rule nobody will dare delete later.

# The entry point the manifest names as a string. AGP keeps it from the merged manifest already;
# this is belt-and-braces for the one class whose loss is a crash on launch.
-keep class cx.m42.restoapp.MainActivity { <init>(); }

# Line numbers survive, so a stack trace in the Play Console can be read with the mapping file.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
