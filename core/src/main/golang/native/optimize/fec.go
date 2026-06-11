package optimize

import (
	"bytes"
	"sync"
	"time"

	"github.com/klauspost/reedsolomon"
)

type AdaptiveFEC struct {
	mu             sync.Mutex
	encoder        reedsolomon.Encoder
	dataShards     int
	redundancy     int
	minRedundancy  int
	maxRedundancy  int
	lossRate       float64
	lastAdjustTime time.Time
	adjustInterval time.Duration
}

func NewAdaptiveFEC(minRedundancy, maxRedundancy int) (*AdaptiveFEC, error) {
	if minRedundancy < 1 {
		minRedundancy = 1
	}
	if maxRedundancy > 32 {
		maxRedundancy = 32
	}
	if minRedundancy > maxRedundancy {
		minRedundancy = maxRedundancy
	}

	encoder, err := reedsolomon.New(8, minRedundancy)
	if err != nil {
		return nil, err
	}

	return &AdaptiveFEC{
		encoder:        encoder,
		dataShards:     8,
		redundancy:     minRedundancy,
		minRedundancy:  minRedundancy,
		maxRedundancy:  maxRedundancy,
		lossRate:       0.0,
		lastAdjustTime: time.Now(),
		adjustInterval: 5 * time.Second,
	}, nil
}

func (f *AdaptiveFEC) UpdateLossRate(lossRate float64) {
	f.mu.Lock()
	f.lossRate = lossRate
	lastAdjust := f.lastAdjustTime
	f.mu.Unlock()

	now := time.Now()
	if now.Sub(lastAdjust) >= f.adjustInterval {
		f.adjustRedundancy()
		f.mu.Lock()
		f.lastAdjustTime = now
		f.mu.Unlock()
	}
}

func (f *AdaptiveFEC) adjustRedundancy() {
	f.mu.Lock()
	defer f.mu.Unlock()

	var targetRedundancy int

	switch {
	case f.lossRate < 0.01:
		targetRedundancy = f.minRedundancy
	case f.lossRate < 0.05:
		targetRedundancy = f.minRedundancy + 1
	case f.lossRate < 0.1:
		targetRedundancy = f.minRedundancy + 2
	case f.lossRate < 0.2:
		targetRedundancy = (f.minRedundancy + f.maxRedundancy) / 2
	default:
		targetRedundancy = f.maxRedundancy
	}

	if targetRedundancy != f.redundancy {
		f.redundancy = targetRedundancy
		var err error
		f.encoder, err = reedsolomon.New(8, f.redundancy)
		if err != nil {
			return
		}
	}
}

func (f *AdaptiveFEC) Encode(data []byte) ([][]byte, error) {
	shards, err := f.encoder.Split(data)
	if err != nil {
		return nil, err
	}

	err = f.encoder.Encode(shards)
	if err != nil {
		return nil, err
	}

	return shards, nil
}

func (f *AdaptiveFEC) Decode(shards [][]byte) ([]byte, error) {
	err := f.encoder.Reconstruct(shards)
	if err != nil {
		return nil, err
	}

	outSize := 0
	for i := 0; i < f.dataShards && i < len(shards); i++ {
		outSize += len(shards[i])
	}

	var buf bytes.Buffer
	err = f.encoder.Join(&buf, shards, outSize)
	if err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func (f *AdaptiveFEC) GetRedundancy() int {
	return f.redundancy
}
