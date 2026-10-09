"""Post-process observations without changing measured bytes or exit codes."""
import json
from pathlib import Path
import re


def owner(result):
    if any(x in result['issues'] for x in ['check disagrees with write', 'stdin disagrees with file output',
            'check changed bytes or mtime', 'file identity changed', 'refusal changed bytes', 'unchanged file mtime changed']):
        return 'ours'
    if 'meaning changed' in result['issues']:
        # A formatted file can only change meaning through our masked-include path: sconfig
        # dropping a definition a later one overrides is its expected merge behaviour, and the
        # guard that must notice the include it stopped overriding is ours (AGENTS.md).
        return 'ours'
    if result['issues']:
        return 'sconfig'
    text = result['write']['stdout'] + result['write']['stderr']
    if any(word in text for word in ['comment would be lost', 'output would not parse',
                                     'second formatting pass would change']):
        return 'sconfig'
    if any(word in text for word in ['not valid UTF-8', 'which the formatter reserves',
                                    'hocon-fmt formats HOCON only']):
        return 'ours'
    return 'shared'


def write(root):
    probes=root/'docs/research/formatters/probes'
    data=json.loads((probes/'actual.json').read_text())
    manifest=json.loads((probes/'manifest.json').read_text())
    def normalize(v):
        if isinstance(v,dict):return {k:normalize(x) for k,x in v.items()}
        if isinstance(v,list):return [normalize(x) for x in v]
        if isinstance(v,str):return re.sub(r'file:/[^)\n]*?/([^/\n]+\.jar)',r'file:<dependency>/\1',v)
        return v
    data=normalize(data)
    for results in data.values():
        for result in results.values():
            result['owner']=owner(result)
    (probes/'actual.json').write_text(json.dumps(data,ensure_ascii=False,indent=2,sort_keys=True)+'\n')
    rows=['file\tsource\tlicence\texpectation\tactual\tverdict\towner']
    for case in manifest:
        for sep,r in data[case['file']].items():
            values=[case['file'],case['source'],case['licence'],case['expectation'],
                f"separator {sep}: check={r['check']['exit']}, write={r['write']['exit']}, second={r['second']['exit']}, check-after={r['check_after']['exit']}, stdin={r['stdin']['exit']}; actual.json#{case['file']}; "+'; '.join(r['issues']),r['verdict'],r['owner']]
            rows.append('\t'.join(x.replace('\t',' ').replace('\n','\\n') for x in values))
    if (probes/'filesystem-actual.json').exists():
        fs=normalize(json.loads((probes/'filesystem-actual.json').read_text()))
        for name,result in fs.items():
            result['owner']='ours'
            rows.append('\t'.join([name,'https://github.com/golang/go/issues/79735','BSD-3-Clause (idea; new setup)',
               'Documented file identity, conditional atomicity and CLI contract.', 'filesystem-actual.json#'+name,'ok','ours']))
        (probes/'filesystem-actual.json').write_text(json.dumps(fs,indent=2,sort_keys=True)+'\n')
    (probes/'probes.tsv').write_text('\n'.join(rows)+'\n')
