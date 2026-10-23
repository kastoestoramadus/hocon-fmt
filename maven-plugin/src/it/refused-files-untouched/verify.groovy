def file = { String path -> new File(basedir, path) }
// setup.groovy stamped every file with this time.
def untouched = { String path, byte[] original ->
  file(path).lastModified() == 1_000_000_000_000L && file(path).bytes == original
}

// Not HOCON at all.
assert untouched('src/main/resources/broken.conf', 'a : ${\n'.bytes)
// A self-referencing substitution the old pipeline refused as unreadable output; the document
// engine keeps both definitions as written.
assert file('src/main/resources/self-reference.conf').text == 'a = 1\na = ${a}\n'
// Not UTF-8: decoding it leniently would write U+FFFD over the é.
assert untouched('src/main/resources/latin1.conf', 'name = "café"\n'.getBytes('ISO-8859-1'))

// The goals did run: the one file they can handle was formatted.
assert file('src/main/resources/valid.conf').text == 'a = 1\n'

def log = file('build.log').text
assert log =~ /\[WARNING\] Leaving src\/main\/resources\/broken.conf unchanged: .*was not closed/
assert log =~ /\[WARNING\] Leaving src\/main\/resources\/latin1.conf unchanged: .*UTF-8/
assert log.contains('2 reformatted, 0 already formatted, 2 refused')
assert log.contains('0 not formatted, 2 already formatted, 2 refused')
