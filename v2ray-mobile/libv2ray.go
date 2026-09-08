// Package libv2ray wraps v2fly/v2ray-core v4 for Android via gomobile.
// It mirrors the Java API surface of 2dust/AndroidLibXrayLite so the
// existing Kotlin code keeps working: Libv2ray.initCoreEnv,
// Libv2ray.newCoreController, CoreController.startLoop/stopLoop/isRunning.
package libv2ray

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"

	core "github.com/v2fly/v2ray-core/v4"
	"github.com/v2fly/v2ray-core/v4/infra/conf/serial"
	"github.com/xjasonlyu/tun2socks/v2/engine"

	// Mandatory features.
	_ "github.com/v2fly/v2ray-core/v4/app/dispatcher"
	_ "github.com/v2fly/v2ray-core/v4/app/proxyman/inbound"
	_ "github.com/v2fly/v2ray-core/v4/app/proxyman/outbound"

	// Optional features used by generated configs.
	// app/dns is excluded: its DoQ support pins an ancient quic-go that
	// does not compile on modern Go. Name resolution falls back to the
	// system resolver, which is fine behind tun2socks.
	_ "github.com/v2fly/v2ray-core/v4/app/log"
	_ "github.com/v2fly/v2ray-core/v4/app/policy"
	_ "github.com/v2fly/v2ray-core/v4/app/router"
	_ "github.com/v2fly/v2ray-core/v4/app/stats"

	// Fix dependency cycle caused by core import in internet package.
	_ "github.com/v2fly/v2ray-core/v4/transport/internet/tagged/taggedimpl"

	// Inbound and outbound proxies.
	_ "github.com/v2fly/v2ray-core/v4/proxy/blackhole"
	_ "github.com/v2fly/v2ray-core/v4/proxy/dokodemo"
	_ "github.com/v2fly/v2ray-core/v4/proxy/freedom"
	_ "github.com/v2fly/v2ray-core/v4/proxy/shadowsocks"
	_ "github.com/v2fly/v2ray-core/v4/proxy/socks"
	_ "github.com/v2fly/v2ray-core/v4/proxy/trojan"
	_ "github.com/v2fly/v2ray-core/v4/proxy/vless/outbound"
	_ "github.com/v2fly/v2ray-core/v4/proxy/vmess/outbound"

	// Transports (no QUIC: its old quic-go dependency does not build on modern Go).
	_ "github.com/v2fly/v2ray-core/v4/transport/internet/grpc"
	_ "github.com/v2fly/v2ray-core/v4/transport/internet/tcp"
	_ "github.com/v2fly/v2ray-core/v4/transport/internet/tls"
	_ "github.com/v2fly/v2ray-core/v4/transport/internet/udp"
	_ "github.com/v2fly/v2ray-core/v4/transport/internet/websocket"

	// Transport headers.
	_ "github.com/v2fly/v2ray-core/v4/transport/internet/headers/http"
	_ "github.com/v2fly/v2ray-core/v4/transport/internet/headers/noop"

	// Geo loaders.
	_ "github.com/v2fly/v2ray-core/v4/infra/conf/geodata/memconservative"
	_ "github.com/v2fly/v2ray-core/v4/infra/conf/geodata/standard"

	// Embeddable JSON config loader.
	_ "github.com/v2fly/v2ray-core/v4/main/jsonem"
)

// CoreCallbackHandler mirrors libv2ray.CoreCallbackHandler on the Java side.
type CoreCallbackHandler interface {
	Startup() int64
	Shutdown() int64
	OnEmitStatus(code int64, message string) int64
}

// CoreController owns one v2ray instance plus its tun2socks engine.
type CoreController struct {
	mu      sync.Mutex
	handler CoreCallbackHandler
	server  *core.Instance
	running bool
}

// InitCoreEnv is kept for API compatibility; v2ray-core needs no env setup.
func InitCoreEnv(envPath string, assetPrefix string) {}

// NewCoreController creates a stopped controller.
func NewCoreController(handler CoreCallbackHandler) *CoreController {
	return &CoreController{handler: handler}
}

// StartLoop parses the JSON config, starts v2ray and attaches the TUN fd
// to the config's socks inbound through tun2socks.
func (c *CoreController) StartLoop(configContent string, fd int) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.running {
		return errors.New("core already running")
	}
	jsonCfg, err := serial.DecodeJSONConfig(strings.NewReader(configContent))
	if err != nil {
		return fmt.Errorf("config parse: %w", err)
	}
	cfg, err := jsonCfg.Build()
	if err != nil {
		return fmt.Errorf("config build: %w", err)
	}
	proxyAddr, err := socksInboundAddr(configContent)
	if err != nil {
		return err
	}
	server, err := core.New(cfg)
	if err != nil {
		return fmt.Errorf("core init: %w", err)
	}
	if err := server.Start(); err != nil {
		_ = server.Close()
		return fmt.Errorf("core start: %w", err)
	}
	engine.Insert(&engine.Key{
		Device:   "fd://" + strconv.Itoa(fd),
		Proxy:    "socks5://" + proxyAddr,
		MTU:      1400,
		LogLevel: "warning",
	})
	engine.Start()
	c.server = server
	c.running = true
	if c.handler != nil {
		c.handler.Startup()
	}
	return nil
}

// StopLoop stops tun2socks and the v2ray instance.
func (c *CoreController) StopLoop() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	engine.Stop()
	if c.server != nil {
		if err := c.server.Close(); err != nil {
			return err
		}
		c.server = nil
	}
	if c.running && c.handler != nil {
		c.handler.Shutdown()
	}
	c.running = false
	return nil
}

// IsRunning reports whether the core loop is up.
func (c *CoreController) IsRunning() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running
}

type inboundProbe struct {
	Tag      string `json:"tag"`
	Listen   string `json:"listen"`
	Port     int    `json:"port"`
	Protocol string `json:"protocol"`
}

func socksInboundAddr(configContent string) (string, error) {
	var parsed struct {
		Inbounds []inboundProbe `json:"inbounds"`
	}
	if err := json.Unmarshal([]byte(configContent), &parsed); err != nil {
		return "", fmt.Errorf("config scan: %w", err)
	}
	for _, in := range parsed.Inbounds {
		if in.Protocol == "socks" && in.Port > 0 {
			listen := in.Listen
			if listen == "" {
				listen = "127.0.0.1"
			}
			return listen + ":" + strconv.Itoa(in.Port), nil
		}
	}
	return "", errors.New("no socks inbound found for tun2socks uplink")
}
