def log = new File(basedir, 'build.log').text
assert log.contains('defined again')
assert new File(basedir, 'src/main/resources/app.conf').text == 'a = 1\na = 2\nb { c = 3 }\n'
