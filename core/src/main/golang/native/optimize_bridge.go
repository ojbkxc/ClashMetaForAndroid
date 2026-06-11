package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/optimize"
	"time"

	"github.com/metacubex/mihomo/log"
)

// A CGo-exported function that panics will terminate the entire Android process.
// All exported functions use defer/recover to prevent panics from crashing the app.

//export enableOptimizer
func enableOptimizer() {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Optimizer] panic in enableOptimizer: %v", r)
		}
	}()

	optimize.Init()
	opt, err := optimize.GetGlobalOptimizer()
	if err != nil {
		log.Errorln("[Optimizer] init failed: %v", err)
		return
	}
	opt.Enable()
	log.Infoln("[Optimizer] enabled (tcpConcurrent=true, dnsTimeout=3s)")
}

//export disableOptimizer
func disableOptimizer() {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Optimizer] panic in disableOptimizer: %v", r)
		}
	}()

	opt, err := optimize.GetGlobalOptimizer()
	if err != nil {
		return
	}
	opt.Disable()
	log.Infoln("[Optimizer] disabled")
}

//export requestOptimizerGC
func requestOptimizerGC() {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Optimizer] panic in requestOptimizerGC: %v", r)
		}
	}()

	opt, err := optimize.GetGlobalOptimizer()
	if err != nil {
		return
	}

	// Close releases all managed resources (connection pools, etc.).
	// The optimizer cannot be re-enabled after this call.
	opt.Close()
	log.Infoln("[Optimizer] shutdown complete")
}

//export periodicOptimizerCleanup
func periodicOptimizerCleanup() {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Optimizer] panic in periodicOptimizerCleanup: %v", r)
		}
	}()

	opt, err := optimize.GetGlobalOptimizer()
	if err != nil {
		return
	}
	opt.PeriodicCleanup()
}

//export setOptimizerDNSTimeout
func setOptimizerDNSTimeout(seconds C.int) {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Optimizer] panic in setOptimizerDNSTimeout: %v", r)
		}
	}()

	opt, err := optimize.GetGlobalOptimizer()
	if err != nil {
		return
	}
	opt.SetDNSTimeout(time.Duration(seconds) * time.Second)
	log.Infoln("[Optimizer] DNS timeout set to %d seconds", int(seconds))
}