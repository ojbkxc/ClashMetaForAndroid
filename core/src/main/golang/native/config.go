package main

//#include "bridge.h"
import "C"

import (
	"runtime"
	"unsafe"

	"cfa/native/config"

	"github.com/metacubex/mihomo/log"
)

type remoteValidCallback struct {
	callback unsafe.Pointer
}

func (r *remoteValidCallback) reportStatus(json string) {
	C.fetch_report(r.callback, marshalString(json))
}

//export fetchAndValid
func fetchAndValid(callback unsafe.Pointer, path, url C.c_string, force C.int) {
	go func(path, url string, callback unsafe.Pointer) {
		defer func() {
			if r := recover(); r != nil {
				log.Errorln("[Config] panic in fetchAndValid: %v", r)
			}
		}()

		cb := &remoteValidCallback{callback: callback}

		err := config.FetchAndValid(path, url, force != 0, cb.reportStatus)

		C.fetch_complete(callback, marshalString(err))

		C.release_object(callback)

		runtime.GC()
	}(C.GoString(path), C.GoString(url), callback)
}

//export load
func load(completable unsafe.Pointer, path C.c_string) {
	go func(path string) {
		defer func() {
			if r := recover(); r != nil {
				log.Errorln("[Config] panic in load: %v", r)

				C.complete(completable, marshalString(r.(error)))
				C.release_object(completable)
			}
		}()

		C.complete(completable, marshalString(config.Load(path)))

		C.release_object(completable)

		runtime.GC()
	}(C.GoString(path))
}

//export readOverride
func readOverride(slot C.int) *C.char {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Config] panic in readOverride: %v", r)
		}
	}()

	return C.CString(config.ReadOverride(config.OverrideSlot(slot)))
}

//export writeOverride
func writeOverride(slot C.int, content C.c_string) {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Config] panic in writeOverride: %v", r)
		}
	}()

	c := C.GoString(content)

	config.WriteOverride(config.OverrideSlot(slot), c)
}

//export clearOverride
func clearOverride(slot C.int) {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Config] panic in clearOverride: %v", r)
		}
	}()

	config.ClearOverride(config.OverrideSlot(slot))
}