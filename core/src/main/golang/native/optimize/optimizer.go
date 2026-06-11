package optimize

import (
	"sync"
	"time"
)

type Optimizer struct {
	mu              sync.Mutex
	fec             *AdaptiveFEC
	retransmit      *SmartRetransmit
	quicConfig      *QUICDynamicConfig
	quicMultipath   *QUICMultipath
	poolManager     *PoolManager
	tcpPool         *TCPConnPool
	dnsCache        *dnsCache
	dnsTimeout      time.Duration
	enabled         bool
}

func NewOptimizer() (*Optimizer, error) {
	fec, err := NewAdaptiveFEC(1, 8)
	if err != nil {
		return nil, err
	}

	return &Optimizer{
		fec:            fec,
		retransmit:     NewSmartRetransmit(),
		quicConfig:     NewQUICDynamicConfig(),
		quicMultipath:  NewQUICMultipath(),
		poolManager:    NewPoolManager(),
		tcpPool:        NewTCPConnPool(200, 30*time.Second, 10),
		dnsTimeout:     5 * time.Second,
		enabled:        true,
	}, nil
}

func (o *Optimizer) Enable() {
	o.mu.Lock()
	o.enabled = true
	o.mu.Unlock()
}

func (o *Optimizer) Disable() {
	o.mu.Lock()
	o.enabled = false
	o.mu.Unlock()
}

func (o *Optimizer) IsEnabled() bool {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.enabled
}

func (o *Optimizer) GetFEC() *AdaptiveFEC {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.fec
}

func (o *Optimizer) GetRetransmit() *SmartRetransmit {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.retransmit
}

func (o *Optimizer) GetQUICConfig() *QUICDynamicConfig {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.quicConfig
}

func (o *Optimizer) GetQUICMultipath() *QUICMultipath {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.quicMultipath
}

func (o *Optimizer) GetPoolManager() *PoolManager {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.poolManager
}

func (o *Optimizer) GetTCPPool() *TCPConnPool {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.tcpPool
}

func (o *Optimizer) SetDNSTimeout(d time.Duration) {
	o.mu.Lock()
	o.dnsTimeout = d
	o.mu.Unlock()
}

func (o *Optimizer) GetDNSTimeout() time.Duration {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.dnsTimeout
}

func (o *Optimizer) SetDNSCache(cache *dnsCache) {
	o.mu.Lock()
	o.dnsCache = cache
	o.mu.Unlock()
}

func (o *Optimizer) GetDNSCache() *dnsCache {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.dnsCache
}

func (o *Optimizer) PeriodicCleanup() {
	o.mu.Lock()
	enabled := o.enabled
	tcpPool := o.tcpPool
	o.mu.Unlock()

	if !enabled || tcpPool == nil {
		return
	}

	tcpPool.Cleanup()
}

func (o *Optimizer) UpdateLossRate(protocol string, lossRate float64) {
	o.mu.Lock()
	enabled := o.enabled
	o.mu.Unlock()

	if !enabled {
		return
	}

	if o.fec != nil {
		o.fec.UpdateLossRate(lossRate)
	}
	if o.quicConfig != nil {
		o.quicConfig.UpdateLossRate(lossRate)
	}
}

func (o *Optimizer) UpdateRTT(protocol string, rtt time.Duration) {
	o.mu.Lock()
	enabled := o.enabled
	o.mu.Unlock()

	if !enabled {
		return
	}

	if o.retransmit != nil {
		o.retransmit.UpdateRTT(rtt)
	}
}

func (o *Optimizer) Close() {
	o.mu.Lock()
	defer o.mu.Unlock()

	o.enabled = false
	o.poolManager.Close()
	if o.tcpPool != nil {
		o.tcpPool.Close()
	}
	// Note: The optimizer is a singleton; after Close() it cannot be re-enabled.
}

var globalOptimizer *Optimizer
var once sync.Once

func GetGlobalOptimizer() (*Optimizer, error) {
	var err error
	once.Do(func() {
		globalOptimizer, err = NewOptimizer()
	})
	return globalOptimizer, err
}
