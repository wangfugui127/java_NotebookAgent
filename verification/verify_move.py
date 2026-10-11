"""Record/verify relevant project bytes across a same-volume directory move."""
from pathlib import Path
import hashlib
import json
import os
import sys

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / 'verification/move-baseline.json'
EXCLUDED = {'.git', 'node_modules', 'target', 'dist', '.npm-cache', '__pycache__', '.venv', 'runtime'}
def snapshot():
    files = {}
    for directory, children, names in os.walk(ROOT):
        children[:] = [name for name in children if name not in EXCLUDED]
        for name in names:
            path = Path(directory) / name
            relative = path.relative_to(ROOT).as_posix()
            if name.endswith('.log') or relative in {'verification/move-baseline.json', 'verification/migration-results.json'}:
                continue
            files[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    for name in ['HEAD', 'index', 'config']:
        path = ROOT / '.git' / name
        if path.is_file():
            files['.git/' + name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return files

if sys.argv[1] == 'capture':
    BASELINE.write_text(json.dumps(snapshot(), ensure_ascii=False, indent=2), encoding='utf-8')
    print('move baseline captured')
else:
    before = json.loads(BASELINE.read_text(encoding='utf-8'))
    after = snapshot()
    changed = [name for name, digest in before.items() if after.get(name) != digest]
    extra = sorted(set(after) - set(before))
    result = {'source': r'F:\Project Practice\notebook\java_notebook', 'destination': str(ROOT),
              'source_absent': not Path(r'F:\Project Practice\notebook\java_notebook').exists(),
              'verified_files': len(before), 'changed_or_missing': changed, 'extra_files': extra}
    (ROOT / 'verification/migration-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=True))
    assert result['source_absent'] and not changed and not extra, 'Directory move verification failed'
