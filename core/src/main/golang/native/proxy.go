package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/proxy"

	"github.com/metacubex/mihomo/log"
)

//export startHttp
func startHttp(listenAt C.c_string) *C.char {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Proxy] panic in startHttp: %v", r)
		}
	}()

	l := C.GoString(listenAt)

	listen, err := proxy.Start(l)
	if err != nil {
		return nil
	}

	return C.CString(listen)
}

//export stopHttp
func stopHttp() {
	defer func() {
		if r := recover(); r != nil {
			log.Errorln("[Proxy] panic in stopHttp: %v", r)
		}
	}()

	proxy.Stop()
}