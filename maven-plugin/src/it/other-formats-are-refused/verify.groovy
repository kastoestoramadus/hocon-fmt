def file = { String path -> new File(basedir, path) }
// setup.groovy stamped every file with this time.
def untouched = { String path, byte[] original ->
  file(path).lastModified() == 1_000_000_000_000L && file(path).bytes == original
}

// Both are readable as HOCON, so only the name decides; a round trip would hand back HOCON with
// the keys reordered, not the file either name promises.
assert untouched('config/application.json', '{\n    "b": 1,\n    "a": 2\n}\n'.bytes)
assert untouched('config/application.properties', 'server.port=8080\n'.bytes)

def log = file('build.log').text
assert log.contains('Leaving config/application.json unchanged: a JSON file, and hocon-fmt formats HOCON only')
assert log.contains(
    'Leaving config/application.properties unchanged: a Java properties file, and hocon-fmt formats HOCON only')
assert log.contains('0 reformatted, 0 already formatted, 2 refused')
assert log.contains('0 not formatted, 0 already formatted, 2 refused')
