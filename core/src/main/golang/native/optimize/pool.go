package optimize

import (
	"context"
	"errors"
	"sync"
	"time"
)

type Conn interface {
	Close() error
}

type ConnPool interface {
	Get(ctx context.Context) (Conn, error)
	Put(conn Conn)
	Close()
	Len() int
}

type pooledConn struct {
	conn    Conn
	addedAt time.Time
}

type BasePool struct {
	mu          sync.Mutex
	conns       []pooledConn
	maxSize     int
	idleTimeout time.Duration
	closed      bool
}

func NewBasePool(maxSize int, idleTimeout time.Duration) *BasePool {
	return &BasePool{
		conns:       make([]pooledConn, 0),
		maxSize:     maxSize,
		idleTimeout: idleTimeout,
		closed:      false,
	}
}

func (p *BasePool) Get(ctx context.Context) (Conn, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.closed {
		return nil, errors.New("pool is closed")
	}

	if ctx != nil {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}
	}

	now := time.Now()
	for len(p.conns) > 0 {
		pc := p.conns[len(p.conns)-1]
		p.conns = p.conns[:len(p.conns)-1]

		if now.Sub(pc.addedAt) <= p.idleTimeout {
			return pc.conn, nil
		}
		pc.conn.Close()
	}

	return nil, errors.New("no available connection")
}

func (p *BasePool) Put(conn Conn) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.closed {
		conn.Close()
		return
	}

	if len(p.conns) < p.maxSize {
		p.conns = append(p.conns, pooledConn{conn: conn, addedAt: time.Now()})
	} else {
		conn.Close()
	}
}

func (p *BasePool) Close() {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.closed {
		return
	}

	p.closed = true
	for _, pc := range p.conns {
		pc.conn.Close()
	}
	p.conns = nil
}

func (p *BasePool) Len() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.conns)
}

func (p *BasePool) Cleanup() {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.closed {
		return
	}

	now := time.Now()
	valid := make([]pooledConn, 0, len(p.conns))
	for _, pc := range p.conns {
		if now.Sub(pc.addedAt) <= p.idleTimeout {
			valid = append(valid, pc)
		} else {
			pc.conn.Close()
		}
	}
	p.conns = valid
}

type PoolManager struct {
	mu       sync.Mutex
	pools    map[string]ConnPool
	defaultMaxSize int
	defaultTimeout time.Duration
}

func NewPoolManager() *PoolManager {
	return &PoolManager{
		pools: make(map[string]ConnPool),
		defaultMaxSize: 10,
		defaultTimeout: 30 * time.Second,
	}
}

func (m *PoolManager) GetPool(protocol string) (ConnPool, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	pool, ok := m.pools[protocol]
	return pool, ok
}

func (m *PoolManager) CreatePool(protocol string, maxSize int, idleTimeout time.Duration) ConnPool {
	m.mu.Lock()
	defer m.mu.Unlock()

	if pool, ok := m.pools[protocol]; ok {
		return pool
	}

	if maxSize <= 0 {
		maxSize = m.defaultMaxSize
	}
	if idleTimeout <= 0 {
		idleTimeout = m.defaultTimeout
	}

	pool := NewBasePool(maxSize, idleTimeout)
	m.pools[protocol] = pool
	return pool
}

func (m *PoolManager) RemovePool(protocol string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if pool, ok := m.pools[protocol]; ok {
		pool.Close()
		delete(m.pools, protocol)
	}
}

func (m *PoolManager) Close() {
	m.mu.Lock()
	defer m.mu.Unlock()

	for _, pool := range m.pools {
		pool.Close()
	}
	m.pools = nil
}

func (m *PoolManager) Cleanup() {
	m.mu.Lock()
	defer m.mu.Unlock()

	for protocol, pool := range m.pools {
		if bp, ok := pool.(*BasePool); ok {
			bp.Cleanup()
			if bp.Len() == 0 {
				pool.Close()
				delete(m.pools, protocol)
			}
		}
	}
}

func (m *PoolManager) GetPoolCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.pools)
}
