package optimize

import (
	"sync"
	"time"
)

type RetransmitItem struct {
	seqNum      uint64
	sendTime    time.Time
	retryCount  int
	maxRetries  int
	callback    func()
}

type SmartRetransmit struct {
	mu              sync.Mutex
	rtt             time.Duration
	rttVariance     time.Duration
	items           map[uint64]*RetransmitItem
	baseTimeout     time.Duration
	maxTimeout      time.Duration
}

func NewSmartRetransmit() *SmartRetransmit {
	return &SmartRetransmit{
		rtt:         100 * time.Millisecond,
		rttVariance: 20 * time.Millisecond,
		items:       make(map[uint64]*RetransmitItem),
		baseTimeout: 50 * time.Millisecond,
		maxTimeout:  5 * time.Second,
	}
}

func (r *SmartRetransmit) UpdateRTT(sample time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()

	alpha := 0.125
	beta := 0.25

	r.rtt = time.Duration(float64(r.rtt)*(1-alpha) + float64(sample)*alpha)
	r.rttVariance = time.Duration(float64(r.rttVariance)*(1-beta) + float64(abs(r.rtt-sample))*beta)
}

func abs(d time.Duration) time.Duration {
	if d < 0 {
		return -d
	}
	return d
}

func (r *SmartRetransmit) GetTimeout() time.Duration {
	r.mu.Lock()
	defer r.mu.Unlock()

	timeout := r.rtt + 4*r.rttVariance
	if timeout < r.baseTimeout {
		timeout = r.baseTimeout
	}
	if timeout > r.maxTimeout {
		timeout = r.maxTimeout
	}

	return timeout
}

func (r *SmartRetransmit) AddItem(seqNum uint64, maxRetries int, callback func()) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.items[seqNum] = &RetransmitItem{
		seqNum:     seqNum,
		sendTime:   time.Now(),
		retryCount: 0,
		maxRetries: maxRetries,
		callback:   callback,
	}
}

func (r *SmartRetransmit) RemoveItem(seqNum uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()

	delete(r.items, seqNum)
}

func (r *SmartRetransmit) CheckAndRetransmit() {
	r.mu.Lock()
	defer r.mu.Unlock()

	now := time.Now()
	// Compute timeout inline to avoid re-acquiring the lock (GetTimeout also locks)
	timeout := r.rtt + 4*r.rttVariance
	if timeout < r.baseTimeout {
		timeout = r.baseTimeout
	}
	if timeout > r.maxTimeout {
		timeout = r.maxTimeout
	}

	for seqNum, item := range r.items {
		if now.Sub(item.sendTime) >= timeout {
			if item.retryCount < item.maxRetries {
				item.retryCount++
				item.sendTime = now
				go item.callback()
			} else {
				delete(r.items, seqNum)
			}
		}
	}
}

func (r *SmartRetransmit) GetRTT() time.Duration {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.rtt
}

// Cleanup removes items that have been pending for too long (e.g., 30s+ without resolution).
// Prevents stale entries from accumulating if callbacks are never triggered.
func (r *SmartRetransmit) Cleanup() {
	r.mu.Lock()
	defer r.mu.Unlock()

	now := time.Now()
	for seqNum, item := range r.items {
		if now.Sub(item.sendTime) > 30*time.Second {
			delete(r.items, seqNum)
		}
	}
}
