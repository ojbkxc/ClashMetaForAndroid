package main

//#include "bridge.h"
import "C"

import (
	"unsafe"

	"cfa/native/app"
	"cfa/native/tunnel"

	"github.com/metacubex/mihomo/log"
)

//export queryTunnelState
func queryTunnelState() *C.char {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Tunnel] panic in queryTunnelState: %v", r)
		}
	}()

	mode := tunnel.QueryMode()

	response := &struct {
		Mode string `json:"mode"`
	}{mode}

	return marshalJson(response)
}

//export queryNow
func queryNow(upload, download *C.uint64_t) {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Tunnel] panic in queryNow: %v", r)
		}
	}()

	up, down := tunnel.Now()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

//export queryTotal
func queryTotal(upload, download *C.uint64_t) {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Tunnel] panic in queryTotal: %v", r)
		}
	}()

	up, down := tunnel.Total()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

//export queryGroupNames
func queryGroupNames(excludeNotSelectable C.int) *C.char {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Tunnel] panic in queryGroupNames: %v", r)
		}
	}()

	return marshalJson(tunnel.QueryProxyGroupNames(excludeNotSelectable != 0))
}

//export queryGroup
func queryGroup(name C.c_string, sortMode C.c_string) *C.char {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Tunnel] panic in queryGroup: %v", r)
		}
	}()

	n := C.GoString(name)
	s := C.GoString(sortMode)

	mode := tunnel.Default

	switch s {
	case "Title":
		mode = tunnel.Title
	case "Delay":
		mode = tunnel.Delay
	}

	response := tunnel.QueryProxyGroup(n, mode, app.SubtitlePattern())

	if response == nil {
		return nil
	}

	return marshalJson(response)
}

//export healthCheck
func healthCheck(completable unsafe.Pointer, name C.c_string) {
	go func(name string) {
		defer func() {
			if r := recover(); r != nil {
				log.Errorln("[Tunnel] panic in healthCheck goroutine: %v", r)
			}
		}()

		tunnel.HealthCheck(name)

		C.complete(completable, nil)
		C.release_object(completable)
	}(C.GoString(name))
}

//export healthCheckAll
func healthCheckAll() {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Tunnel] panic in healthCheckAll: %v", r)
		}
	}()

	tunnel.HealthCheckAll()
}

//export patchSelector
func patchSelector(selector, name C.c_string) C.int {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Tunnel] panic in patchSelector: %v", r)
		}
	}()

	s := C.GoString(selector)
	n := C.GoString(name)

	if tunnel.PatchSelector(s, n) {
		return 1
	}

	return 0
}

//export queryProviders
func queryProviders() *C.char {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Tunnel] panic in queryProviders: %v", r)
		}
	}()

	return marshalJson(tunnel.QueryProviders())
}

//export updateProvider
func updateProvider(completable unsafe.Pointer, pType C.c_string, name C.c_string) {
	go func(pType, name string) {
		defer func() {
			if r := recover(); r != nil {
				log.Errorln("[Tunnel] panic in updateProvider goroutine: %v", r)

				C.complete(completable, marshalString(r.(error)))
				C.release_object(completable)
			}
		}()

		C.complete(completable, marshalString(tunnel.UpdateProvider(pType, name)))

		C.release_object(completable)
	}(C.GoString(pType), C.GoString(name))
}

//export suspend
func suspend(suspended C.int) {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Tunnel] panic in suspend: %v", r)
		}
	}()

	tunnel.Suspend(suspended != 0)
}
