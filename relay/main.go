package main

import (
    "context"
    "crypto/tls"
    "fmt"
    "log"
    "net"
    "os"
    "sync"
    "time"

    "github.com/quic-go/quic-go"
)

const (
    defaultBind = ":443"
)

type Session struct {
    ID          string
    CreatedAt   time.Time
    Paths       map[string]*PathState
    mu          sync.Mutex
    conn        quic.Connection
    packetCount uint64
}

type PathState struct {
    ID        string
    LastSeen  time.Time
    RTT       time.Duration
    LossRate  float64
    Sequence  uint64
    Pending   map[uint64][]byte
    mu        sync.Mutex
}

func main() {
    bind := os.Getenv("RELAY_BIND")
    if bind == "" {
        bind = defaultBind
    }

    certPath := os.Getenv("RELAY_TLS_CERT")
    keyPath := os.Getenv("RELAY_TLS_KEY")
    if certPath == "" || keyPath == "" {
        log.Fatal("RELAY_TLS_CERT and RELAY_TLS_KEY must be set")
    }

    tlsConf, err := loadTLSConfig(certPath, keyPath)
    if err != nil {
        log.Fatalf("load tls config: %v", err)
    }

    listener, err := quic.ListenAddr(bind, tlsConf, &quic.Config{
        EnableDatagrams: true,
        KeepAlivePeriod: 15 * time.Second,
    })
    if err != nil {
        log.Fatalf("listen: %v", err)
    }
    defer listener.Close()

    log.Printf("Multipath relay listening on %s", bind)

    for {
        conn, err := listener.Accept(context.Background())
        if err != nil {
            log.Printf("accept error: %v", err)
            continue
        }
        go handleSession(conn)
    }
}

func loadTLSConfig(certPath, keyPath string) (*tls.Config, error) {
    cert, err := tls.LoadX509KeyPair(certPath, keyPath)
    if err != nil {
        return nil, err
    }
    return &tls.Config{
        Certificates: []tls.Certificate{cert},
        MinVersion:   tls.VersionTLS12,
    }, nil
}

func handleSession(conn quic.Connection) {
    defer conn.CloseWithError(0, "session ended")

    session := &Session{
        ID:        fmt.Sprintf("sess-%d", time.Now().UnixNano()),
        CreatedAt: time.Now(),
        Paths:     make(map[string]*PathState),
        conn:      conn,
    }

    log.Printf("new client session: %s", session.ID)

    for {
        msg, err := conn.ReceiveDatagram(context.Background())
        if err != nil {
            log.Printf("datagram receive failed: %v", err)
            return
        }

        if len(msg) == 0 {
            continue
        }

        if err := handleDatagram(session, msg); err != nil {
            log.Printf("datagram error: %v", err)
        }
    }
}

func handleDatagram(session *Session, payload []byte) error {
    // A real relay would parse a MASQUE/CONNECT-IP-style header including session/path/flow ids.
    // This scaffold keeps only the minimum structure required to show the relay logic.
    session.mu.Lock()
    session.packetCount++
    session.mu.Unlock()

    // Example: take a packet and forward it to the destination on a real socket.
    if len(payload) < 4 {
        return nil
    }

    // Forward to a destination using the system socket; this is intentionally minimal and is not
    // production-grade routing logic. A real service would parse packet headers and reassemble.
    dest := net.JoinHostPort("1.1.1.1", "443")
    conn, err := net.DialTimeout("tcp", dest, 5*time.Second)
    if err != nil {
        return err
    }
    defer conn.Close()
    if _, err := conn.Write(payload); err != nil {
        return err
    }
    return nil
}
