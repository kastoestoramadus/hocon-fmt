def log = new File(basedir, 'build.log').text
assert log.contains('defined again')
assert log.contains('Not formatted: src/main/resources/other.conf')
