// TURN relay servers for the optional "Juggluco connect" (ICE) feature.
// Empty by default upstream. This stub provides a single inert placeholder entry so
// the default_turn_servers[] array is non-empty (a zero-size array is illegal in C++).
// Replace with real entries to enable TURN relaying, e.g.:
//   { .host="relay1.expressturn.com", .username="...", .password="...", .port=3480 },
{ .host="", .username="", .password="", .port=0 },
