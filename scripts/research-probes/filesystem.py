"""Filesystem/CLI setup cases, kept separate from portable text inputs."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def run(CMD):
    results={}
    def invoke(work,args):
        p=subprocess.run(CMD+args,cwd=work,capture_output=True,timeout=45)
        return dict(exit=p.returncode,stdout=p.stdout.decode(errors='backslashreplace').replace(str(work),'<work>'),stderr=p.stderr.decode(errors='backslashreplace').replace(str(work),'<work>'))
    with tempfile.TemporaryDirectory(prefix='hocon-research-fs-') as directory:
        work=Path(directory);(work/'.git').mkdir()
        target=work/'target.conf';target.write_text('a=  1')
        link=work/'alias.conf';link.symlink_to(target.name)
        before=target.stat();result=invoke(work,[link.name]);after=target.stat()
        results['symlink']=dict(result=result,link_kept=link.is_symlink(),actual=target.read_text(),mode_kept=before.st_mode==after.st_mode,owner_kept=(before.st_uid,before.st_gid)==(after.st_uid,after.st_gid))
        target.write_text('a=  1');target.chmod(0o444);before=target.stat();data=target.read_bytes()
        result=invoke(work,[target.name]);after=target.stat()
        results['read-only']=dict(result=result,bytes_kept=data==target.read_bytes(),mtime_kept=before.st_mtime_ns==after.st_mtime_ns,mode=oct(after.st_mode&0o7777))
        target.chmod(0o644)
        results['missing']=dict(result=invoke(work,['--check','missing.conf']))
        results['stdin-check-invalid-combination']=dict(result=invoke(work,['--stdin','--check']))
        (work/'.hocon-fmt.conf').write_text('separator=":"\n')
        target.write_text('a=1');results['config-file']=dict(result=invoke(work,[target.name]),actual=target.read_text())
        p=subprocess.run(CMD+['--stdin','--stdin-filename',str(target)],cwd=work,input=b'a=1',capture_output=True,timeout=45)
        results['stdin-filename-no-lookup']=dict(exit=p.returncode,stdout=p.stdout.decode(),stderr=p.stderr.decode())
        (work/'.hocon-fmt.conf').write_text('unknown=true\n');target.write_text('a=1');data=target.read_bytes()
        results['config-invalid']=dict(result=invoke(work,[target.name]),bytes_kept=data==target.read_bytes())
        (work/'.hocon-fmt.conf').unlink()
        (work/'.gitignore').write_text('ignored.conf\n')
        (work/'ignored.conf').write_text('a=1')
        results['ignored-directory']=dict(result=invoke(work,['--check','.']),ignored_kept=(work/'ignored.conf').read_text()=='a=1')
        results['ignored-explicit']=dict(result=invoke(work,['--check','ignored.conf']))
        (work/'clean.conf').write_text('a = 1\n');(work/'bad.conf').write_text('a={')
        results['mixed-clean-refused']=dict(result=invoke(work,['--check','clean.conf','bad.conf']))
        results['mixed-dirty-refused']=dict(result=invoke(work,['--check','ignored.conf','bad.conf']))
        results['mixed-missing-dirty']=dict(result=invoke(work,['--check','missing.conf','ignored.conf']))
        target.write_text('a=1');hard=work/'hard.conf';os.link(target,hard)
        results['hardlink-atomic']=dict(result=invoke(work,[target.name]),target=target.read_text(),hardlink=hard.read_text(),same_inode=target.stat().st_ino==hard.stat().st_ino)
    # Observe a process killed when its actual write path begins. The in-place outcome is conditional.
    for mode in ['atomic','fallback']:
        with tempfile.TemporaryDirectory(prefix='hocon-research-kill-') as directory:
            work=Path(directory);(work/'.git').mkdir();target=work/'large.conf'
            data=b'a="'+b'x'*8000000+b'"';target.write_bytes(data)
            if mode=='fallback':work.chmod(0o555)
            observed=False;signal='';deadline=time.monotonic()+45
            p=subprocess.Popen(CMD+[target.name],cwd=work,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            while p.poll() is None and time.monotonic()<deadline:
                if mode=='atomic' and any(x.name not in {'.git','large.conf'} for x in work.iterdir()):
                    observed=True;signal='staging file appeared';p.kill();break
                if mode=='fallback' and target.stat().st_size!=len(data):
                    observed=True;signal='target size changed';p.kill();break
                time.sleep(.001)
            if p.poll() is None:p.kill()
            stdout,stderr=p.communicate();work.chmod(0o755)
            actual=target.read_bytes()
            results['kill-'+mode]=dict(exit=p.returncode,write_observed=observed,signal=signal,original_kept=actual==data,remaining_bytes=len(actual),stdout=stdout.decode(errors='backslashreplace').replace(str(work),'<work>'),stderr=stderr.decode(errors='backslashreplace').replace(str(work),'<work>'))
    return results
