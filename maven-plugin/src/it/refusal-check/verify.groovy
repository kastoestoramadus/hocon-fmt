def log = new File(basedir, 'build.log').text
assert new File(basedir, 'src/main/resources/refused.conf').text == 'a : ${\n'
assert new File(basedir, 'src/main/resources/acceptable.conf').text == 'a = 1\n'
assert log.contains('HOCON formatting refusals found.')
