package main

/*
#cgo LDFLAGS: -llog

#include "bridge.h"
*/
import "C"

import (
	"runtime"
	"runtime/debug"
	"time"

	"cfa/native/config"
	"cfa/native/delegate"
	"cfa/native/optimize"
	"cfa/native/tunnel"

	"github.com/metacubex/mihomo/log"
)

func main() {
	panic("Stub!")
}

//export coreInit
func coreInit(home, versionName, gitVersion C.c_string, sdkVersion C.int) {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[APP] panic in coreInit: %v", r)
		}
	}()

	h := C.GoString(home)
	v := C.GoString(versionName)
	g := C.GoString(gitVersion)
	s := int(sdkVersion)

	delegate.Init(h, v, g, s)

	reset()

	// Aggressive GC to keep memory footprint low on mobile devices.
	// GC triggers when heap grows by 10% (vs default 100%), preventing OOM kills.
	debug.SetGCPercent(10)

	// Socket optimizations must be applied before any network activity
	// (UDP buffers, TCP_NODELAY, IP_MTU_DISCOVER for PMTUD)
	optimize.SetupSocketHook()

	// Lazy init optimizer in background - does not block startup
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Errorln("[APP] panic in optimize.Init: %v", r)
			}
		}()
		optimize.Init()
	}()

	// Periodic forced GC every 3 minutes to return unused heap memory to the OS,
	// further reducing the risk of OOM process kill on memory-constrained devices.
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Errorln("[APP] panic in periodicGC: %v", r)
			}
		}()
		for {
			time.Sleep(3 * time.Minute)
			runtime.GC()
			debug.FreeOSMemory()
		}
	}()
}

//export reset
func reset() {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[APP] panic in reset: %v", r)
		}
	}()

	config.LoadDefault()
	tunnel.ResetStatistic()
	tunnel.CloseAllConnections()

	runtime.GC()
	debug.FreeOSMemory()
}

//export forceGc
func forceGc() {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[APP] panic in forceGc: %v", r)
		}
	}()

	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Errorln("[APP] panic in forceGc goroutine: %v", r)
			}
		}()

		log.Infoln("[APP] request force GC")

		runtime.GC()
		debug.FreeOSMemory()
	}()
}