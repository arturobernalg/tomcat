# JMH benchmark for MessageBytes.toBytes() (BZ 69552)

Measures `MessageBytes.toBytes()` for an ASCII `String`, an ASCII `char[]` and a `String` with a non-ASCII character,
with the UTF-8 charset. It is not part of the Tomcat build. It only needs `tomcat-util.jar` of the build
being measured. See https://github.com/apache/tomcat/pull/1155 for the same setup applied to EL.

Run with `-p charsetName=UTF-8 -f 1 -wi 3 -i 5 -prof gc` against `tomcat-util.jar` of `main` (before) and of the pull
request (after). JDK 21, JMH 1.37. The raw output is in `results-before.txt` and `results-after.txt`.

| Benchmark, UTF-8 | Before ns/op | After ns/op | Faster | Before B/op | After B/op |
|---|---|---|---|---|---|
| ASCII `String` | 105 +/- 7 | 22 +/- 1 | 4.7x | 280 | 64 |
| ASCII `char[]` | 112 +/- 10 | 25 +/- 1 | 4.4x | 280 | 64 |
| non-ASCII `String` | 118 +/- 4 | 122 +/- 6 | 1.0x | 264 | 264 |
