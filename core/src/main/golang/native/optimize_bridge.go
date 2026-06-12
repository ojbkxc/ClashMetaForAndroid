package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/optimize"
	"time"

	"github.com/metacubex/mihomo/log"
)

//export enableOptimizer
func enableOptimizer() {
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
	opt, err := optimize.GetGlobalOptimizer()
	if err != nil {
		return
	}
	opt.Disable()
	log.Infoln("[Optimizer] disabled")
}

//export requestOptimizerGC
func requestOptimizerGC() {
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
	opt, err := optimize.GetGlobalOptimizer()
	if err != nil {
		return
	}
	opt.PeriodicCleanup()
}

//export setOptimizerDNSTimeout
func setOptimizerDNSTimeout(seconds C.int) {
	opt, err := optimize.GetGlobalOptimizer()
	if err != nil {
		return
	}
	opt.SetDNSTimeout(time.Duration(seconds) * time.Second)
	log.Infoln("[Optimizer] DNS timeout set to %d seconds", int(seconds))
}