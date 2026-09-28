"""Standard-library artifact sanity checks, not a general JSON Schema validator."""
import json
import re
from pathlib import Path

schema = json.loads(Path('docs/api/v1.schema.json').read_text())
fixture = json.loads(Path('docs/api/event-v1.json').read_text())
pattern = schema['$defs']['sequence']['pattern']
for value in ['0', '1', '9007199254740993', '9223372036854775807']:
	assert re.fullmatch(pattern, value), value
for value in ['-1', '01', '1.0', '9223372036854775808', '9999999999999999999']:
	assert not re.fullmatch(pattern, value), value
assert re.fullmatch(pattern, fixture['sequence'])


def check_refs(node):
	if isinstance(node, dict):
		if '$ref' in node:
			assert node['$ref'].split('/')[-1] in schema['$defs']
		for value in node.values():
			check_refs(value)
	elif isinstance(node, list):
		for value in node:
			check_refs(value)


check_refs(schema)
assert set(fixture) == set(schema['$defs']['event']['required'])
for path in Path('.').glob('intruvia-*/src/**/*.java'):
	for number, line in enumerate(path.read_text().splitlines(), 1):
		assert len(line) < 160, (path, number, len(line))
print('PASS: JSON parsing, local schema references, complete envelope keys,')
print('decimal sequence boundaries and Java line-length review.')
print('Not run: full JSON Schema validator (Python jsonschema is unavailable).')
