package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/optimize"

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
	log.Infoln("[Optimizer] enabled")
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