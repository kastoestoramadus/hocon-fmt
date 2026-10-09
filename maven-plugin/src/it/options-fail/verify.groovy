def log = new File(basedir, 'build.log').text
assert log.contains('defined again')
assert log.contains('duplicate definitions found')
