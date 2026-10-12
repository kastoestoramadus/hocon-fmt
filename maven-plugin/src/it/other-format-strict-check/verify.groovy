assert new File(basedir, 'data.json').text == 'a = 1\n'
assert new File(basedir, 'app.properties').text == 'a = 1\n'
assert new File(basedir, 'build.log').text.contains('HOCON formatting refusals found.')
