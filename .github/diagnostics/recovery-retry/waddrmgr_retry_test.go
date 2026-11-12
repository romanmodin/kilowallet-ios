package waddrmgr

import (
	"github.com/btcsuite/btcwallet/walletdb"
	"strings"
	"testing"
)

// Synthetic DB only: initial birthday persistence, recovery progress, then a stale retry.
func TestKilowalletStaleBirthdayRetry(t *testing.T) {
	teardown, db, _ := setupManager(t)
	defer teardown()
	birthday := BlockStamp{Height: 961639}
	birthday.Hash[0] = 1
	err := walletdb.Update(db, func(tx walletdb.ReadWriteTx) error {
		ns := tx.ReadWriteBucket(waddrmgrNamespaceKey)
		if err := PutSyncedTo(ns, &birthday); err != nil {
			return err
		}
		if err := PutBirthdayBlock(ns, birthday); err != nil {
			return err
		}
		next := BlockStamp{Height: 961640}
		next.Hash[0] = 2
		return PutSyncedTo(ns, &next)
	})
	if err != nil {
		t.Fatal(err)
	}
	err = walletdb.Update(db, func(tx walletdb.ReadWriteTx) error {
		return PutSyncedTo(tx.ReadWriteBucket(waddrmgrNamespaceKey), &birthday)
	})
	if err == nil || !strings.Contains(err.Error(), "961638") {
		t.Fatalf("expected exact local index failure, got %v", err)
	}
	t.Logf("reproduced: %v", err)
	err = walletdb.View(db, func(tx walletdb.ReadTx) error {
		ns := tx.ReadBucket(waddrmgrNamespaceKey)
		b, err := FetchBirthdayBlock(ns)
		if err != nil {
			return err
		}
		if b.Height != birthday.Height || b.Hash != birthday.Hash || !b.Timestamp.Equal(birthday.Timestamp) {
			t.Fatal("birthday changed")
		}
		tip, err := fetchSyncedTo(ns)
		if err != nil {
			return err
		}
		if tip.Height != 961640 {
			t.Fatal("recovery progress changed")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}
