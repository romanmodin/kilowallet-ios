package wallet

import (
	"github.com/btcsuite/btcwallet/waddrmgr"
	"github.com/btcsuite/btcwallet/walletdb"
	"testing"
	"time"
)

func TestKilowalletRetryUsesPersistedBirthday(t *testing.T) {
	w, cleanup := testWallet(t)
	defer cleanup()
	before := w.Manager.Birthday()
	b, err := w.persistedBirthdayForRetry()
	if err != nil || b != nil {
		t.Fatalf("fresh wallet: %v %v", b, err)
	}
	birthday := waddrmgr.BlockStamp{Height: 961639, Timestamp: time.Unix(1788220800, 0)}
	birthday.Hash[0] = 1
	err = walletdb.Update(w.db, func(tx walletdb.ReadWriteTx) error {
		ns := tx.ReadWriteBucket(waddrmgrNamespaceKey)
		if err := w.Manager.SetSyncedTo(ns, &birthday); err != nil {
			return err
		}
		if err := w.Manager.SetBirthdayBlock(ns, birthday, true); err != nil {
			return err
		}
		tip := birthday
		tip.Height++
		tip.Hash[0] = 2
		return w.Manager.SetSyncedTo(ns, &tip)
	})
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		b, err = w.persistedBirthdayForRetry()
		if err != nil {
			t.Fatal(err)
		}
		if b == nil || b.Height != birthday.Height || b.Hash != birthday.Hash || !b.Timestamp.Equal(birthday.Timestamp) {
			t.Fatalf("birthday changed: %v", b)
		}
		if w.Manager.SyncedTo().Height != 961640 {
			t.Fatal("recovery progress rewound")
		}
		if !w.Manager.Birthday().Equal(before) {
			t.Fatal("wallet birthday changed")
		}
	}
	err = walletdb.Update(w.db, func(tx walletdb.ReadWriteTx) error {
		return w.Manager.SetBirthdayBlock(tx.ReadWriteBucket(waddrmgrNamespaceKey), birthday, false)
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = w.persistedBirthdayForRetry(); err == nil {
		t.Fatal("unverified persisted birthday accepted")
	}
}
