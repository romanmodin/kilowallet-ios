"""Additional release evidence checks. Never substitutes for source gates or phone testing."""
import json,re,sys
from pathlib import Path
from zipfile import ZipFile
def check_engines(log):
    patterns={
      'arkBackupRestored':r'KW ark-live: backup round trip: file [1-9][0-9]* bytes, db [1-9][0-9]* bytes, wal [0-9]+, derived true; after restore: .*ArkModel\.View\.Ready',
      'lightningUsesTor':r'KW ln-live: node running, Tor in LND: true, SOCKS [1-9][0-9]*',
      'onionPeerConnected':r'KW ln-live: connect Kilombino by onion: OK; peers \{[^\n]*\.onion:9735',
      'watchtowerRegistered':r'KW ln-live: towers \[\([^\n]*\.onion:9911\], true\)',
      'emptyChannelBackupRpc':r'KW ln-live: own channel backup [1-9][0-9]* bytes; RestoreChannelBackups by RPC: OK',
      'torStarted':r'KW tor-live: Tor SOCKS port [1-9][0-9]* ',
    }
    checks={k:bool(re.search(v,log)) for k,v in patterns.items()}
    tls=re.findall(r'KW tor-live: Electrum through Tor: ([^\n]+)',log)
    checks['electrumEvidencePresent']=bool(tls)
    return {'checks':checks,'passed':all(checks.values()),'electrumResult':tls,
            'physicalPhoneTested':False,'fundedChannelRestoreTested':False,
            'note':'Empty-channel RPC and tower registration do not prove funded recovery or accepted tower backups. Inspect Electrum result separately; a rejected unapproved certificate is not a successful scan.'}
if __name__ == '__main__':
    directory = Path(sys.argv[1])
    log = "\n".join(p.read_text(errors='replace') for p in sorted(directory.glob('*.log')))
    result = check_engines(log)
    print(json.dumps(result, indent=2))
    sys.exit(not result['passed'])
