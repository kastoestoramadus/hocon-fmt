assert new File(basedir, 'src/main/resources/app.conf').text == new File(basedir, 'expected.conf').text
assert new File(basedir, 'build.log').text.contains('defined again')
