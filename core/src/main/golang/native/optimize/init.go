package optimize

import (
	"runtime"
	"sync"
)

var initOnce sync.Once

// Init ensures the optimizer is initialized and ready.
// Safe to call multiple times; only initializes once.
func Init() {
	initOnce.Do(func() {
		_, _ = GetGlobalOptimizer()
		runtime.GC()
	})
}