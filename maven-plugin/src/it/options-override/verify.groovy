def log = new File(basedir, 'build.log').text
assert log.contains('defined again')
assert new File(basedir, 'src/main/resources/app.conf').text == 'a = 2\nb {\n    c = 3\n}\n'
