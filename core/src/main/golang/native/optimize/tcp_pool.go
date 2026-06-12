package optimize

import (
	"context"
	"net"
	"sync"
	"time"
)

// TCPConnPool caches raw TCP connections for reuse, reducing TCP/TLS handshake overhead.
// It implements the NetDialer interface so it can be used as a dialer option.
type TCPConnPool struct {
	mu          sync.Mutex
	pools       map[string]*connQueue // key: "network:address"
	maxSize     int
	idleTimeout time.Duration
	maxPerHost  int
	closed      bool
	dialFn      func(ctx context.Context, network, address string) (net.Conn, error)
}

type connQueue struct {
	conns []*pooledTCPConn
}

type pooledTCPConn struct {
	conn    net.Conn
	addedAt time.Time
}

// NewTCPConnPool creates a new TCP connection pool.
func NewTCPConnPool(maxSize int, idleTimeout time.Duration, maxPerHost int) *TCPConnPool {
	if maxPerHost <= 0 {
		maxPerHost = 5
	}
	return &TCPConnPool{
		pools:       make(map[string]*connQueue),
		maxSize:     maxSize,
		idleTimeout: idleTimeout,
		maxPerHost:  maxPerHost,
	}
}

func (p *TCPConnPool) SetDialFunc(fn func(ctx context.Context, network, address string) (net.Conn, error)) {
	p.dialFn = fn
}

func (p *TCPConnPool) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	key := network + ":" + address

	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		if p.dialFn != nil {
			return p.dialFn(ctx, network, address)
		}
		var d net.Dialer
		return d.DialContext(ctx, network, address)
	}

	q, ok := p.pools[key]
	if ok && len(q.conns) > 0 {
		now := time.Now()
		for len(q.conns) > 0 {
			pc := q.conns[len(q.conns)-1]
			q.conns = q.conns[:len(q.conns)-1]
			if now.Sub(pc.addedAt) <= p.idleTimeout {
				p.mu.Unlock()
				return pc.conn, nil
			}
			pc.conn.Close()
		}
	}
	p.mu.Unlock()

	if p.dialFn != nil {
		return p.dialFn(ctx, network, address)
	}
	var d net.Dialer
	return d.DialContext(ctx, network, address)
}

func (p *TCPConnPool) Put(network, address string, conn net.Conn) {
	key := network + ":" + address

	p.mu.Lock()
	defer p.mu.Unlock()

	if p.closed {
		conn.Close()
		return
	}

	q, ok := p.pools[key]
	if !ok {
		q = &connQueue{}
		p.pools[key] = q
	}

	if len(q.conns) >= p.maxPerHost || p.totalSize() >= p.maxSize {
		conn.Close()
		return
	}

	q.conns = append(q.conns, &pooledTCPConn{
		conn:    conn,
		addedAt: time.Now(),
	})
}

func (p *TCPConnPool) totalSize() int {
	total := 0
	for _, q := range p.pools {
		total += len(q.conns)
	}
	return total
}

func (p *TCPConnPool) Cleanup() {
	p.mu.Lock()
	defer p.mu.Unlock()

	now := time.Now()
	for key, q := range p.pools {
		valid := make([]*pooledTCPConn, 0, len(q.conns))
		for _, pc := range q.conns {
			if now.Sub(pc.addedAt) <= p.idleTimeout {
				valid = append(valid, pc)
			} else {
				pc.conn.Close()
			}
		}
		if len(valid) == 0 {
			delete(p.pools, key)
		} else {
			q.conns = valid
		}
	}
}

func (p *TCPConnPool) Close() {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.closed = true
	for _, q := range p.pools {
		for _, pc := range q.conns {
			pc.conn.Close()
		}
	}
	p.pools = nil
}

func (p *TCPConnPool) Len() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.totalSize()
}