"""Run each recorded byte fixture against the actual JVM CLI in an isolated directory."""
import collections
import concurrent.futures
import difflib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import filesystem
import ledger

ROOT = Path(__file__).resolve().parents[2]
PROBES = ROOT / 'docs/research/formatters/probes'
CP, BUILD = sys.argv[1:3]
RECORD = '--record' in sys.argv[3:]
ORACLE_ONLY = '--oracle-only' in sys.argv[3:]
OBSERVED = {}
ONLY = sys.argv[sys.argv.index('--only') + 1].split(',') if '--only' in sys.argv else []
# UsePerfData off: parallel short-lived JVMs otherwise race on /tmp/hsperfdata_<user> and print
# a locked-file warning that made the recorded stdout differ between replays.
CMD = ['java', '-XX:ActiveProcessorCount=2', '-XX:-UsePerfData', '-Xss4m', '-cp', CP, os.environ.get('HOCON_RESEARCH_MAIN_CLASS', 'ww86.hocon_fmt.CmdApi')]


def comments(data):
    # Independent source lexer; ignore strings, and retain duplicate normalized comment bodies.
    text = data.decode('utf-8', errors='strict')
    found = []
    i = 0
    while i < len(text):
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end < 0:
                break
            i = end + 3
            while i < len(text) and text[i] == '"':
                i += 1
        elif text[i] == '"':
            i += 1
            while i < len(text):
                if text[i] == '\\':
                    i += 2
                elif text[i] == '"':
                    i += 1
                    break
                else:
                    i += 1
        elif text[i] == '#' or text.startswith('//', i):
            start = i + (1 if text[i] == '#' else 2)
            end = text.find('\n', start)
            if end < 0:
                end = len(text)
            found.append(text[start:end].strip(''.join(chr(c) for c in range(33))))
            i = end
        else:
            i += 1
    return collections.Counter(found)


def cli(args, cwd, data=None):
    try:
        p = subprocess.run(CMD + args, cwd=cwd, input=data, capture_output=True, timeout=45)
        return {'exit': p.returncode,
                'stdout': p.stdout.decode('utf-8', errors='backslashreplace'),
                'stderr': p.stderr.decode('utf-8', errors='backslashreplace')}
    except subprocess.TimeoutExpired:
        return {'exit': 'TIMEOUT', 'stdout': '', 'stderr': '45-second budget exceeded'}


def normalize(result, directory):
    def value(v):
        if not isinstance(v, str):
            return v
        v = v.replace(str(directory), '<work>')
        for entry in CP.split(os.pathsep):
            if entry.endswith('.jar'):
                v = v.replace(entry, '<dependency>/' + Path(entry).name)
        return v
    return {k: value(v) for k, v in result.items()}


def owner(result):
    return ledger.owner(result)


def run_case(case):
    data = (PROBES / case['file']).read_bytes()
    results = {}
    for separator in ['=', ':']:
        with tempfile.TemporaryDirectory(prefix='hocon-research-') as name:
            work = Path(name)
            # No ancestor style lookup; all include oracles have deterministic contents.
            (work / '.git').mkdir()
            (work / 'f.conf').write_bytes((PROBES / 'support/f.conf').read_bytes())
            (work / 'scalar.conf').write_bytes((PROBES / 'support/scalar.conf').read_bytes())
            target = work / case['file']
            target.write_bytes(data)
            flags = ['--separator', separator] + case.get('args', [])
            stat = target.stat()
            check = cli(flags + ['--check', target.name], work)
            after_check = target.read_bytes()
            checked_stat = target.stat()
            written = cli(flags + [target.name], work)
            first = target.read_bytes()
            stat_first = target.stat()
            second = cli(flags + [target.name], work)
            final = target.read_bytes()
            stat_second = target.stat()
            checked = cli(flags + ['--check', target.name], work)
            # Filter mode is a separate contract, exercised on every verdict.
            stdin = cli(flags + ['--stdin', '--stdin-filename', target.name], work, data)
            source = work / ('source' + target.suffix)
            source.write_bytes(data)
            bare = work / 'bare.txt'
            oracle = subprocess.run(['java', '-XX:-UsePerfData', '-Xss4m', '-cp', CP + os.pathsep + BUILD,
                                     'Oracle', str(source), str(target), separator, str(bare),
                                     str('--no-simplify-nested-objects' not in case.get('args', [])).lower(),
                                     str('--double-indent' in case.get('args', [])).lower()],
                                    cwd=work, capture_output=True, timeout=45)
            evidence = oracle.stdout.decode('utf-8', errors='backslashreplace').strip()
            # sconfig's unresolved-merge banner names the source path; hex would otherwise carry
            # this run's temporary directory and never match the recording.
            bare_hex = bare.read_bytes().replace(str(work).encode(), b'<work>').hex() if bare.exists() else None
            issues = []
            refused = 'cannot format, leaving unchanged' in written['stderr'] + written['stdout']
            verdict = 'refused as designed' if refused else 'ok'
            if after_check != data or checked_stat.st_mtime_ns != stat.st_mtime_ns:
                issues.append('check changed bytes or mtime')
            if (stat.st_mode, stat.st_uid, stat.st_gid) != (stat_first.st_mode, stat_first.st_uid, stat_first.st_gid):
                issues.append('file identity changed')
            if check['exit'] != (1 if first != data else 0):
                issues.append('check disagrees with write')
            if refused and first != data:
                issues.append('refusal changed bytes')
            if final != first or checked['exit'] != 0:
                issues.append('second pass not fixed point')
            if first == data and (stat.st_mtime_ns != stat_first.st_mtime_ns):
                issues.append('unchanged file mtime changed')
            if stat_first.st_mtime_ns != stat_second.st_mtime_ns:
                issues.append('second write mtime changed')
            if not refused:
                try:
                    if comments(data) != comments(first):
                        issues.append('comment multiset changed')
                except UnicodeDecodeError:
                    issues.append('invalid UTF-8 accepted')
                if 'MEANING-CHANGED' in evidence:
                    issues.append('meaning changed')
                if 'LITERAL-CHANGED' in evidence:
                    issues.append('literal content changed')
                if stdin['exit'] != 0 or stdin['stdout'].encode() != first:
                    issues.append('stdin disagrees with file output')
            elif stdin['exit'] != 1 or stdin['stdout']:
                issues.append('stdin refusal contract')
            if written['exit'] != 0:
                issues.append('unexpected write failure/crash')
            if issues:
                verdict = 'BUG'
            results[separator] = dict(verdict=verdict,issues=issues,
                check=normalize(check,work),write=normalize(written,work),
                second=normalize(second,work),check_after=normalize(checked,work),
                stdin=normalize(stdin,work),actual_hex=first.hex(),bare_sconfig_hex=bare_hex,
                oracle=normalize({'text':evidence,'exit':oracle.returncode,'stderr':oracle.stderr.decode(errors='backslashreplace')},work),
                mode_preserved=(stat.st_mode == stat_first.st_mode),
                owner_preserved=(stat.st_uid,stat.st_gid)==(stat_first.st_uid,stat_first.st_gid))
    return case['file'], results


def refresh_oracle(case):
    previous = OBSERVED[case['file']]
    for separator, r in previous.items():
        with tempfile.TemporaryDirectory(prefix='hocon-research-') as name:
            work = Path(name)
            (work / 'f.conf').write_bytes((PROBES / 'support/f.conf').read_bytes())
            (work / 'scalar.conf').write_bytes((PROBES / 'support/scalar.conf').read_bytes())
            source = work / ('source' + Path(case['file']).suffix)
            source.write_bytes((PROBES / case['file']).read_bytes())
            target = work / case['file']
            target.write_bytes(bytes.fromhex(r['actual_hex']))
            bare = work / 'bare.txt'
            p = subprocess.run(['java', '-XX:ActiveProcessorCount=2', '-XX:-UsePerfData', '-Xss4m', '-cp', CP + os.pathsep + BUILD,
                                'Oracle', str(source), str(target), separator, str(bare),
                                     str('--no-simplify-nested-objects' not in case.get('args', [])).lower(),
                                     str('--double-indent' in case.get('args', [])).lower()],
                               cwd=work, capture_output=True, timeout=45)
            evidence = p.stdout.decode(errors='backslashreplace').strip()
            r['bare_sconfig_hex'] = bare.read_bytes().replace(str(work).encode(), b'<work>').hex() if bare.exists() else None
            r['oracle'] = normalize(dict(text=evidence, exit=p.returncode,
                                         stderr=p.stderr.decode(errors='backslashreplace')), work)
            if 'LITERAL-CHANGED' in evidence and r['verdict'] != 'refused as designed':
                r['issues'].append('literal content changed')
                r['verdict'] = 'BUG'
    return case['file'], previous


def main():
    global OBSERVED
    manifest = json.loads((PROBES / 'manifest.json').read_text())
    actual = json.loads((PROBES / 'actual.json').read_text()) if ONLY or ORACLE_ONLY else {}
    OBSERVED = actual
    selected = [c for c in manifest if not ONLY or c['file'] in ONLY]
    with concurrent.futures.ThreadPoolExecutor(max_workers=int(os.environ.get("HOCON_RESEARCH_JOBS", "12"))) as pool:
        for i, (file, result) in enumerate(pool.map(refresh_oracle if ORACLE_ONLY else run_case, selected),1):
            actual[file] = result
            if i % 25 == 0:
                print(f'{i}/{len(manifest)} inputs completed', flush=True)
    for result in actual.values():
        for r in result.values():
            r['owner'] = owner(r)
    text = json.dumps(actual,ensure_ascii=False,indent=2,sort_keys=True)+'\n'
    baseline = PROBES / 'actual.json'
    if RECORD:
        baseline.write_text(text)
        rows=['file\tsource\tlicence\texpectation\tactual\tverdict\towner']
        for case in manifest:
            for sep,r in actual[case['file']].items():
                values=[case['file'],case['source'],case['licence'],case['expectation'],
                        f"separator {sep}: check={r['check']['exit']}, write={r['write']['exit']}, second={r['second']['exit']}, check-after={r['check_after']['exit']}, stdin={r['stdin']['exit']}; actual.json#{case['file']}; "+'; '.join(r['issues']),r['verdict'],r['owner']]
                rows.append('\t'.join(x.replace('\t',' ').replace('\n','\\n') for x in values))
        (PROBES / 'probes.tsv').write_text('\n'.join(rows)+'\n')
    elif baseline.read_text()!=text:
        with tempfile.NamedTemporaryFile(prefix='hocon-probe-current-', suffix='.json', mode='w', delete=False) as f:
            f.write(text)
            current = f.name
        diff = list(difflib.unified_diff(baseline.read_text().splitlines(True),text.splitlines(True),fromfile='recorded',tofile='current'))
        print(''.join(diff[:120]))
        print(f'Full current observations: {current}; {len(diff)} diff lines')
        return 1
    if not ONLY and not ORACLE_ONLY:
        fs = filesystem.run(CMD)
        def clean(value):
            if isinstance(value, dict):
                return {k: clean(v) for k, v in value.items()}
            if isinstance(value, list):
                return [clean(v) for v in value]
            if isinstance(value, str):
                return normalize({'v': value}, Path('/nonexistent'))['v']
            return value
        fs = clean(fs)
        for r in fs.values():
            r['owner'] = 'ours'
        fs_path = PROBES / 'filesystem-actual.json'
        if RECORD:
            fs_path.write_text(json.dumps(fs, indent=2, sort_keys=True) + '\n')
        else:
            expected = json.loads(fs_path.read_text())
            # Kill windows vary: compare the contract (observed write and bytes preserved),
            # retaining the exact byte count from the recording as observational evidence.
            for key in ['kill-atomic', 'kill-fallback']:
                fs[key].pop('remaining_bytes', None)
                expected[key].pop('remaining_bytes', None)
            if fs != expected:
                print('Filesystem observations changed:', json.dumps(fs, indent=2))
                return 1
    if RECORD:
        ledger.write(ROOT)
    counts=collections.Counter(r['verdict'] for row in actual.values() for r in row.values())
    print(dict(counts))
    return 0

if __name__=='__main__':
    sys.exit(main())
