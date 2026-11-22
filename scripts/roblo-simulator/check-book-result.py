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
    access = {'publicIds': [910001, 910002, 910003],
              'denied': ['write', 'credentialHeader', 'otherHeader', 'browserCredentials',
                         'query', 'otherPath', 'otherCoordinator', 'otherOrigin']}
    if not (data.get('version') == 2 and data.get('access') == access and data.get('matches') == expected):
        raise SystemExit('Order book did not show the two expected filtered results.')
