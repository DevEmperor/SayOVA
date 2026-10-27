# Keep line numbers so crash reports from Play remain readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Conversation history and the pairing payload are (de)serialized with kotlinx.serialization, whose
# generated serializers are found without reflection; the library ships the rules it needs.
