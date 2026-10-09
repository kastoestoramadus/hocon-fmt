def log = new File(basedir, 'build.log').text
assert log.contains('double-indent')
assert log.contains('maybe')
assert new File(basedir, 'src/main/resources/app.conf').text == 'a=1\n'
