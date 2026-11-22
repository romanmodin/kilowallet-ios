"""Validate observations from the synthetic order book in the isolated preview."""
import json
import sys
from pathlib import Path

expected = {
    'usd': {'rowId': 'kilobot/910001', 'currency': 'usd', 'amount': '73'},
    'eur': {'rowId': 'kilobot/910003', 'currency': 'eur', 'amount': '96'},
}

if __name__ == '__main__':
    data = json.loads(Path(sys.argv[1]).read_text())
    if not (data.get('version') == 1 and type(data.get('fixtureReads')) is int and
            data['fixtureReads'] > 0 and data.get('matches') == expected):
        raise SystemExit('Order book did not show the two expected filtered results.')
