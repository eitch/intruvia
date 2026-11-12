# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
from collections import Counter
from datetime import datetime
from math import ceil
import json

root = Path('docs/verification')
results = json.loads((root / '020-capacity-measurements.json').read_text())
fixture = (root / '020-capacity-fixture.txt').read_text()
assert 'POOL=maximum10,acquisition30000ms,validation5000ms' in fixture
assert 'HTTP_FAILURE=' not in fixture
assert 'COUNTS=100000,3100,EventStreamState[lastCommittedSequence=103100, minAfter=3100]' in fixture
assert len(results) == 2

def percentile(values):
	return sorted(values)[ceil(len(values) * .95) - 1]

def epoch(value):
	return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp() * 1000

for result in results:
	items = result['items']
	assert len(items) == 3100
	assert result['failures'] == [] and result['hidden'] is False and result['rows'] == 1000
	assert [item['sequence'] for item in items] == [str(n) for n in range(100001, 103101)]
	assert Counter(item['instance'] for item in items) == {'server-a': 1550, 'server-b': 1550}
	sustained = [epoch(item['occurredAt']) for item in items[:3000]]
	burst = [epoch(item['occurredAt']) for item in items[3000:]]
	assert 299000 <= max(sustained) - min(sustained) <= 301000
	assert max(burst) - min(burst) <= 1000
	for key, limit in [('endToEnd', 1000), ('receiptToFrame', 100), ('serverToFrame', 1000)]:
		measured = percentile([item[key] for item in items])
		assert measured <= limit, (result['summary']['browser'], key, measured)
		print('Browser', result['summary']['browser'], key, 'combined p95 ms:', measured)
	print('Sustained send span ms:', round(max(sustained) - min(sustained), 3))
	print('100-worker burst send span ms:', round(max(burst) - min(burst), 3))
	print('Burst send-to-frame p95 ms:', percentile([item['endToEnd'] for item in items[3000:]]))
	print('Burst server-received-to-frame p95 ms:', percentile([item['serverToFrame'] for item in items[3000:]]))
print('PASS: two identities/two visible contexts, all3100 durable commits and ordered independent indications;')
print('100000 retained bound,3100 receipts, actual five-minute sends and concurrent100-event burst.')
print('Combined workload p95 gates pass; phase/tail latency remains explicitly reported.')
