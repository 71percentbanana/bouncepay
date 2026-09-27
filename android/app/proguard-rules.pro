# Release builds are not minified (see build.gradle.kts), so no rules are
# needed yet. If minification is turned on, keep the JSON field names used on
# the wire — they are read by name in Packet.fromJson and by the bank.
