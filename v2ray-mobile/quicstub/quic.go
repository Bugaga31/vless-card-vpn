// Package quic is a compile-time stub replacing github.com/lucas-clemente/quic-go.
// v2ray-core v4 imports it in app/dns (DoQ) and transport/internet/quic, but the
// pinned quic-go v0.27 does not build on modern Go. Our configs never use QUIC,
// so any call path into this stub fails loudly at runtime instead.
package quic

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"time"
)

var errStub = errors.New("quic-go stub: QUIC is not available in this build")

// ApplicationErrorCode mirrors quic-go v0.27.
type ApplicationErrorCode uint64

// Config mirrors the fields v2ray-core sets.
type Config struct {
	HandshakeIdleTimeout time.Duration
	MaxIdleTimeout       time.Duration
	ConnectionIDLength   int
	KeepAlive            bool
	MaxIncomingStreams   int64
	MaxIncomingUniStreams int64
}

// Stream mirrors quic-go v0.27's Stream.
type Stream interface {
	Read(p []byte) (int, error)
	Write(p []byte) (int, error)
	Close() error
	SetDeadline(t time.Time) error
	SetReadDeadline(t time.Time) error
	SetWriteDeadline(t time.Time) error
}

// Connection mirrors the subset of quic-go v0.27's Connection used by v2ray-core.
type Connection interface {
	Context() context.Context
	Close() error
	CloseWithError(code ApplicationErrorCode, desc string) error
	OpenStream() (Stream, error)
	OpenStreamSync(ctx context.Context) (Stream, error)
	AcceptStream(ctx context.Context) (Stream, error)
	LocalAddr() net.Addr
	RemoteAddr() net.Addr
}

// Listener mirrors quic-go v0.27's Listener.
type Listener interface {
	Accept(ctx context.Context) (Connection, error)
	Addr() net.Addr
	Close() error
}

// Dial always fails: QUIC is unsupported in this build.
func Dial(conn net.PacketConn, remoteAddr net.Addr, host string, tlsConf *tls.Config, conf *Config) (Connection, error) {
	return nil, errStub
}

// DialContext always fails.
func DialContext(ctx context.Context, conn net.PacketConn, remoteAddr net.Addr, host string, tlsConf *tls.Config, conf *Config) (Connection, error) {
	return nil, errStub
}

// DialAddrContext always fails.
func DialAddrContext(ctx context.Context, addr string, tlsConf *tls.Config, conf *Config) (Connection, error) {
	return nil, errStub
}

// Listen always fails.
func Listen(conn net.PacketConn, tlsConf *tls.Config, conf *Config) (Listener, error) {
	return nil, errStub
}
