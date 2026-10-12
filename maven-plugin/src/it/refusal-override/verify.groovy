def log = new File(basedir, 'build.log').text
assert new File(basedir, 'src/main/resources/refused.conf').text == '# only a comment\n'
assert new File(basedir, 'src/main/resources/acceptable.conf').text == 'a = 1\n'
