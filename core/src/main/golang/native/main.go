package main

/*
#cgo LDFLAGS: -llog

#include "bridge.h"
*/
import "C"

import (
	"runtime"
	"runtime/debug"

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

	// Reduce GC target to lower memory peak on mobile devices
	debug.SetGCPercent(50)

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
		log.Infoln("[APP] request force GC")

		runtime.GC()
		debug.FreeOSMemory()
	}()
}