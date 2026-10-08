// The build ran with -X, so build.log carries the plugin realm Maven populated for the goal.
def log = new File(basedir, 'build.log').text

// The plugin resolves the Java API, and the core arrives through it: both are on the realm the
// goal loads in, and both are compile dependencies in the resolved tree.
assert log.contains('Included: eu.ww86:hocon-fmt-java-api:jar:')
assert log.contains('Included: eu.ww86:hocon-fmt-core_3:jar:')
assert log =~ /\[DEBUG\]\s+eu\.ww86:hocon-fmt-java-api:jar:[^:]+:compile/
assert log =~ /\[DEBUG\]\s+eu\.ww86:hocon-fmt-core_3:jar:[^:]+:compile/

// The realm was populated for a real goal: the formatted file was judged by the API verdicts.
assert log.contains('0 not formatted, 1 already formatted, 0 refused')
