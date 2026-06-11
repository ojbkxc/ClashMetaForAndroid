package optimize

import (
	"sync"
	"time"

	"github.com/metacubex/quic-go"
)

type QUICDynamicConfig struct {
	mu                sync.Mutex
	initialWindowSize uint32
	minWindowSize     uint32
	maxWindowSize     uint32
	currentWindowSize uint32
	lossRate          float64
	lastAdjustTime    time.Time
	adjustInterval    time.Duration
}

func NewQUICDynamicConfig() *QUICDynamicConfig {
	return &QUICDynamicConfig{
		initialWindowSize: 65536,
		minWindowSize:     16384,
		maxWindowSize:     1048576,
		currentWindowSize: 65536,
		lossRate:          0.0,
		lastAdjustTime:    time.Now(),
		adjustInterval:    3 * time.Second,
	}
}

func (q *QUICDynamicConfig) UpdateLossRate(lossRate float64) {
	q.mu.Lock()
	q.lossRate = lossRate
	q.mu.Unlock()

	now := time.Now()
	if now.Sub(q.lastAdjustTime) >= q.adjustInterval {
		q.adjustWindowSize()
		q.lastAdjustTime = now
	}
}

func (q *QUICDynamicConfig) adjustWindowSize() {
	q.mu.Lock()
	defer q.mu.Unlock()

	var targetWindow uint32

	switch {
	case q.lossRate < 0.01:
		targetWindow = q.maxWindowSize
	case q.lossRate < 0.05:
		targetWindow = q.maxWindowSize * 3 / 4
	case q.lossRate < 0.1:
		targetWindow = q.maxWindowSize / 2
	case q.lossRate < 0.2:
		targetWindow = q.maxWindowSize / 4
	default:
		targetWindow = q.minWindowSize
	}

	q.currentWindowSize = targetWindow
}

func (q *QUICDynamicConfig) GetWindowSize() uint32 {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.currentWindowSize
}

type QUICMultipath struct {
	mu             sync.Mutex
	enabled        bool
	paths          []quic.Connection
	activePath     int
	pathScores     []float64
	lastSwitchTime time.Time
	switchInterval time.Duration
}

func NewQUICMultipath() *QUICMultipath {
	return &QUICMultipath{
		enabled:        false,
		paths:          make([]quic.Connection, 0),
		activePath:     0,
		pathScores:     make([]float64, 0),
		lastSwitchTime: time.Now(),
		switchInterval: 10 * time.Second,
	}
}

func (m *QUICMultipath) Enable() {
	m.mu.Lock()
	m.enabled = true
	m.mu.Unlock()
}

func (m *QUICMultipath) Disable() {
	m.mu.Lock()
	m.enabled = false
	m.mu.Unlock()
}

func (m *QUICMultipath) AddPath(conn quic.Connection) {
	m.mu.Lock()
	m.paths = append(m.paths, conn)
	m.pathScores = append(m.pathScores, 1.0)
	m.mu.Unlock()
}

func (m *QUICMultipath) RemovePath(index int) {
	m.mu.Lock()
	if index >= 0 && index < len(m.paths) {
		m.paths = append(m.paths[:index], m.paths[index+1:]...)
		m.pathScores = append(m.pathScores[:index], m.pathScores[index+1:]...)
		if m.activePath >= len(m.paths) {
			m.activePath = 0
		}
	}
	m.mu.Unlock()
}

func (m *QUICMultipath) UpdatePathScore(index int, score float64) {
	m.mu.Lock()
	if index >= 0 && index < len(m.pathScores) {
		m.pathScores[index] = score
	}
	m.mu.Unlock()

	m.trySwitchPath()
}

func (m *QUICMultipath) trySwitchPath() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.enabled || len(m.paths) < 2 {
		return
	}

	now := time.Now()
	if now.Sub(m.lastSwitchTime) < m.switchInterval {
		return
	}

	bestIndex := 0
	bestScore := m.pathScores[0]

	for i, score := range m.pathScores {
		if score > bestScore {
			bestScore = score
			bestIndex = i
		}
	}

	if bestIndex != m.activePath {
		m.activePath = bestIndex
		m.lastSwitchTime = now
	}
}

func (m *QUICMultipath) GetActivePath() quic.Connection {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.enabled || len(m.paths) == 0 {
		return nil
	}

	return m.paths[m.activePath]
}

func (m *QUICMultipath) GetPathCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.paths)
}
