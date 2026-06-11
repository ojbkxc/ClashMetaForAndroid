package optimize

import (
	"runtime"
	"sync"
	"time"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/resolver"
)

var initOnce sync.Once

// Init ensures the optimizer is initialized and ready.
// Safe to call multiple times; only initializes once.
func Init() {
	initOnce.Do(func() {
		_, _ = GetGlobalOptimizer()

		// Enable TCP concurrent dialing for faster connection establishment
		dialer.SetTcpConcurrent(true)

		// Set DNS timeout to 3s for faster fallback
		resolver.DefaultDNSTimeout = 3 * time.Second

		// Install DNS cache (5 minute TTL)
		SetupDNSCache(300)

		runtime.GC()
	})
}