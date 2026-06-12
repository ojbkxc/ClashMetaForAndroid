// +build debug

package main

import (
	"net/http"
	_ "net/http/pprof"

	"github.com/metacubex/mihomo/log"
)

func init() {
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Warnln("[Debug] pprof panic: %v", r)
			}
		}()

		log.Debugln("pprof service listen at: 0.0.0.0:8888")

		_ = http.ListenAndServe("0.0.0.0:8888", nil)
	}()
}
