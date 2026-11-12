package wallet

import (
	"encoding/binary"
	"errors"
	"github.com/btcsuite/btcd/btcutil"
	"github.com/btcsuite/btcd/chaincfg/chainhash"
	"github.com/btcsuite/btcd/wire"
	"github.com/btcsuite/btcwallet/chain"
	"github.com/btcsuite/btcwallet/waddrmgr"
	"github.com/btcsuite/btcwallet/walletdb"
	"sync"
	"testing"
	"time"
)

const retryBirthdayHeight int32 = 961639
const retryTipHeight int32 = retryBirthdayHeight + 2001

type retryChain struct {
	mockChainClient
	birthday      time.Time
	notifications chan interface{}
	failed        chan struct{}
	release       chan struct{}
	rescanned     chan chainhash.Hash
	mu            sync.Mutex
	batches       [][2]int32
}

func retryHash(h int32) chainhash.Hash {
	var hash chainhash.Hash
	binary.LittleEndian.PutUint32(hash[:4], uint32(h))
	return hash
}
func (c *retryChain) GetBestBlock() (*chainhash.Hash, int32, error) {
	h := retryHash(retryTipHeight)
	return &h, retryTipHeight, nil
}
func (c *retryChain) GetBlockHash(h int64) (*chainhash.Hash, error) {
	hash := retryHash(int32(h))
	return &hash, nil
}
func (c *retryChain) GetBlockHeader(hash *chainhash.Hash) (*wire.BlockHeader, error) {
	h := int32(binary.LittleEndian.Uint32(hash[:4]))
	stamp := c.birthday
	if h < retryBirthdayHeight {
		stamp = stamp.Add(-3 * time.Hour)
	}
	if h > retryBirthdayHeight {
		stamp = stamp.Add(3 * time.Hour)
	}
	return &wire.BlockHeader{Timestamp: stamp}, nil
}
func (c *retryChain) IsCurrent() bool                   { return true }
func (c *retryChain) BackEnd() string                   { return "neutrino" }
func (c *retryChain) Notifications() <-chan interface{} { return c.notifications }
func (c *retryChain) FilterBlocks(req *chain.FilterBlocksRequest) (*chain.FilterBlocksResponse, error) {
	c.mu.Lock()
	c.batches = append(c.batches, [2]int32{req.Blocks[0].Height, req.Blocks[len(req.Blocks)-1].Height})
	n := len(c.batches)
	c.mu.Unlock()
	if n == 2 {
		close(c.failed)
		<-c.release
		return nil, errors.New("synthetic filter timeout after committed recovery batch")
	}
	return nil, nil
}
func (c *retryChain) Rescan(hash *chainhash.Hash, _ []btcutil.Address, _ map[wire.OutPoint]btcutil.Address) error {
	c.rescanned <- *hash
	return nil
}

// Exercise the actual ClientConnected -> waitForSync -> syncWithChain loop.
// Only synthetic headers, empty filters and a temporary wallet database exist.
func TestKilowalletAsyncRecoveryRetry(t *testing.T) {
	w, cleanup := testWallet(t)
	defer cleanup()
	defer func() { w.Stop(); w.WaitForShutdown(); w.db.Close() }()
	w.recoveryWindow = 1
	birthdayTime := w.Manager.Birthday()
	addr, err := w.NewAddress(waddrmgr.DefaultAccountNum, waddrmgr.KeyScopeBIP0084)
	if err != nil {
		t.Fatal(err)
	}
	before, err := w.AddressInfo(addr)
	if err != nil {
		t.Fatal(err)
	}
	pubBefore := before.(waddrmgr.ManagedPubKeyAddress).ExportPubKey()
	c := &retryChain{birthday: birthdayTime, notifications: make(chan interface{}, 1), failed: make(chan struct{}), release: make(chan struct{}), rescanned: make(chan chainhash.Hash, 1)}
	var once sync.Once
	release := func() { once.Do(func() { close(c.release) }) }
	defer release()
	w.chainClientLock.Lock()
	w.chainClient = nil
	w.chainClientLock.Unlock()
	w.SynchronizeRPC(c)
	c.notifications <- chain.ClientConnected{}
	select {
	case <-c.failed:
	case <-time.After(20 * time.Second):
		t.Fatal("did not reach injected recovery failure")
	}
	// The real recovery committed its first 2000-block batch before timeout.
	if tip := w.Manager.SyncedTo(); tip.Height != retryBirthdayHeight+2000 {
		t.Fatalf("progress before retry: %v", tip)
	}
	var initial waddrmgr.BlockStamp
	err = walletdb.View(w.db, func(tx walletdb.ReadTx) error {
		ns := tx.ReadBucket(waddrmgrNamespaceKey)
		b, _, e := w.Manager.BirthdayBlock(ns)
		if e != nil {
			return e
		}
		initial = b
		_, e = w.Manager.BlockHash(ns, retryBirthdayHeight-1)
		if !waddrmgr.IsError(e, waddrmgr.ErrBlockNotFound) {
			t.Fatalf("expected absent predecessor, got %v", e)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if initial.Height != retryBirthdayHeight {
		t.Fatalf("unexpected initial birthday %v", initial)
	}
	release()
	select {
	case h := <-c.rescanned:
		if h != retryHash(retryTipHeight) {
			t.Fatalf("rescan rewound: %v", h)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("retry did not reach rescan; possible repeated initial-sync write")
	}
	// Stop the asynchronous workers before inspecting final persisted state.
	w.Stop()
	w.WaitForShutdown()
	if tip := w.Manager.SyncedTo(); tip.Height != retryTipHeight || tip.Hash != retryHash(retryTipHeight) {
		t.Fatalf("final tip %v", tip)
	}
	final, err := w.persistedBirthdayForRetry()
	if err != nil {
		t.Fatal(err)
	}
	if final == nil || final.Height != initial.Height || final.Hash != initial.Hash || !final.Timestamp.Equal(initial.Timestamp) {
		t.Fatalf("birthday changed: %v -> %v", initial, final)
	}
	if !w.Manager.Birthday().Equal(birthdayTime) {
		t.Fatal("wallet birthday changed")
	}
	after, err := w.AddressInfo(addr)
	if err != nil {
		t.Fatal(err)
	}
	if after.Address().EncodeAddress() != addr.EncodeAddress() || after.(waddrmgr.ManagedPubKeyAddress).ExportPubKey() != pubBefore {
		t.Fatal("persisted address identity changed")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	want := [][2]int32{{961640, 963639}, {963640, 963640}, {963640, 963640}}
	if len(c.batches) != len(want) {
		t.Fatalf("batches %v", c.batches)
	}
	for i := range want {
		if c.batches[i] != want[i] {
			t.Fatalf("batches %v", c.batches)
		}
	}
	t.Logf("real async retry: committed batch, injected timeout, resumed last block; birthday and address unchanged; batches=%v", c.batches)
}
