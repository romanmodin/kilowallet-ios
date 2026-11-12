"""Diagnostic-only stage markers. Never use this modified build for release qualification."""
import argparse, hashlib, json, pathlib, subprocess
SOURCE = "c4f487efc9b328263f15d91c51670d919a9b31b9"
BASE = "app/src/commonMain/kotlin/com/kilombino/kilowallet/app/"
SPECS = {
 BASE+"ui/App.kt": ("cb8d408ab785c40606475362da64709880010f05b783726af31cbb5673e5e4c0", [
 ('            I18n.ensure(I18n.lang.value) // the screenshot waits for the table, not English first', '            platform.markReady("trace-before-language")\n            I18n.ensure(I18n.lang.value) // the screenshot waits for the table, not English first\n            platform.markReady("trace-after-language")'),
 ('            model.setUpDemo(it)', '            model.setUpDemo(it)\n            platform.markReady("trace-setup-returned")')]),
 BASE+"WalletModel.kt": ("59cdf47b6447ce521bef7899c49e9a0f660b3aa374a46a5174720e923b5faa0d", [
 ('    fun setUpDemo(screen: String) {', '    fun setUpDemo(screen: String) {\n        platform.markReady("trace-setup-entered")'),
 ('                // Official public BIP84 fixture, independently derived by CoreTest.bip84OfficialVector.', '                platform.markReady("trace-model-coroutine-started")\n                // Official public BIP84 fixture, independently derived by CoreTest.bip84OfficialVector.'),
 ('                    wallets = listOf(WalletInfo(WalletCatalog.LEGACY_ID, "Everyday", WalletKind.HOT, zpub)), screen = Screen.Home) }', '                    wallets = listOf(WalletInfo(WalletCatalog.LEGACY_ID, "Everyday", WalletKind.HOT, zpub)), screen = Screen.Home) }\n                platform.markReady("trace-home-state-set")'),
 ('                        val notes = transactionNotes.save(zpub, txid, note.label, note.note)', '                        val notes = transactionNotes.save(zpub, txid, note.label, note.note)\n                        platform.markReady("trace-notes-saved")'),
 ('                        if (screen != "history") platform.markReady(screen) // History signals after its rows are laid out.', '                        platform.markReady("trace-history-state-populated")\n                        if (screen != "history") platform.markReady(screen) // History signals after its rows are laid out.')]),
 BASE+"ui/Home.kt": ("986f672c0aa0f2300583b6f90d86cb265c1af7de622e1b804f14d230863ad077", [
 ('            val bottom = scroll.maxValue', '            platform.markReady("trace-history-layout-effect")\n            val bottom = scroll.maxValue'),
 ('            scroll.scrollTo(bottom)', '            platform.markReady("trace-history-first-frame")\n            scroll.scrollTo(bottom)\n            platform.markReady("trace-history-scrolled")')])
}
def transform(path, raw):
    digest, replacements = SPECS[path]
    assert hashlib.sha256(raw).hexdigest() == digest, "Source hash changed: "+path
    text = raw.decode()
    for old, new in replacements:
        assert text.count(old) == 1, (path, old)
        text = text.replace(old, new)
    return text.encode()
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--apply", action="store_true")
    args=parser.parse_args();root=pathlib.Path(args.root)
    assert subprocess.check_output(["git","-C",str(root),"rev-parse","HEAD"],text=True).strip()==SOURCE
    changes={p:transform(p,(root/p).read_bytes()) for p in SPECS}
    receipt={"baseSource":SOURCE,"diagnosticOnly":True,"releaseApproved":False,"files":[{"path":p,"before":SPECS[p][0],"after":hashlib.sha256(raw).hexdigest()} for p,raw in changes.items()]}
    if args.apply:
        for p,raw in changes.items():(root/p).write_bytes(raw)
        out=root/"build/history-trace";out.mkdir(parents=True,exist_ok=True)
        (out/"source-receipt.json").write_text(json.dumps(receipt,indent=2)+"\n")
        (out/"instrumentation.diff").write_bytes(subprocess.check_output(["git","-C",str(root),"diff","--",*SPECS]))
    print(json.dumps(receipt,indent=2))
if __name__=="__main__": main()
