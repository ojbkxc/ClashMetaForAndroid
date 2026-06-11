package optimize

import (
	"time"

	"github.com/klauspost/reedsolomon"
)

type AdaptiveFEC struct {
	encoder        reedsolomon.Encoder
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
		redundancy:     minRedundancy,
		minRedundancy:  minRedundancy,
		maxRedundancy:  maxRedundancy,
		lossRate:       0.0,
		lastAdjustTime: time.Now(),
		adjustInterval: 5 * time.Second,
	}, nil
}

func (f *AdaptiveFEC) UpdateLossRate(lossRate float64) {
	f.lossRate = lossRate

	now := time.Now()
	if now.Sub(f.lastAdjustTime) >= f.adjustInterval {
		f.adjustRedundancy()
		f.lastAdjustTime = now
	}
}

func (f *AdaptiveFEC) adjustRedundancy() {
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

	return f.encoder.Join(shards)
}

func (f *AdaptiveFEC) GetRedundancy() int {
	return f.redundancy
}
