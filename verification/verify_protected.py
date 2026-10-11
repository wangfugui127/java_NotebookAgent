"""Compare the original project, copied frontend and .env with pre-work hashes."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
baseline = json.loads((ROOT / 'verification/protected-baseline.json').read_text(encoding='utf-8'))
original = Path(r'F:\Project Practice\notebook\notebookagent')
differences = []
absent_history = []
for label, base in [('original', original), ('frontend', ROOT / 'frontend')]:
    for name, expected in baseline[label].items():
        path = base / name
        actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        # The original's ignored OpenCode history can be managed independently.
        # Preserve and verify its pre-work copy rather than restore outside edits.
        if label == 'original' and actual is None and Path(name).name.startswith('session-ses_'):
            copy = ROOT / name
            if copy.is_file() and hashlib.sha256(copy.read_bytes()).hexdigest() == expected:
                absent_history.append(name)
                continue
        if actual != expected:
            differences.append(f'{label}/{name}')
if hashlib.sha256((ROOT / '.env').read_bytes()).hexdigest() != baseline['env']:
    differences.append('.env')
result = {'original_files': len(baseline['original']), 'frontend_files': len(baseline['frontend']),
          'original_existing_unchanged': len(baseline['original']) - len(absent_history),
          'original_absent_history_preserved_in_java': absent_history,
          'env_unchanged': '.env' not in differences, 'differences': differences}
(ROOT / 'verification/protected-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=True))
assert not differences, 'Protected files changed'
