#!/bin/bash
# Niren Drama - Quick start/restart from project root
set -e

export PATH="/usr/bin:/bin:/mingw64/bin:/d/apache-maven-3.9.16/bin:/d/software/nodejs:$PATH"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PORT=8080
FRONTEND_PORT=5173

echo "============================================"
echo "  Niren Drama - Start / Restart"
echo "============================================"
echo ""

# ---- kill existing ----
kill_port() {
    local port=$1
    local pids
    pids=$(netstat -ano | grep ":$port " | grep "LISTENING" | awk '{print $NF}' | sort -u)
    for pid in $pids; do
        echo "[STOP] Killing PID $pid (port $port)"
        taskkill //pid "$pid" //f 2>/dev/null || true
    done
}

RESTARTING=false
if netstat -ano | grep -q ":$BACKEND_PORT .*LISTENING" 2>/dev/null; then
    RESTARTING=true
fi
if netstat -ano | grep -q ":$FRONTEND_PORT .*LISTENING" 2>/dev/null; then
    RESTARTING=true
fi

kill_port $BACKEND_PORT
kill_port $FRONTEND_PORT

if $RESTARTING; then
    echo "[WAIT] Ports releasing..."
    sleep 3
    echo ""
fi

# ---- start backend ----
echo "[START] Backend (Spring Boot :$BACKEND_PORT)"
cd "$PROJECT_DIR/backend"
mvn spring-boot:run > /tmp/niren-backend.log 2>&1 &
BACKEND_PID=$!
echo "       PID: $BACKEND_PID, log: /tmp/niren-backend.log"

# ---- start frontend ----
echo "[START] Frontend (Vite :$FRONTEND_PORT)"
cd "$PROJECT_DIR/frontend"
npm run dev -- --host 0.0.0.0 > /tmp/niren-frontend.log 2>&1 &
FRONTEND_PID=$!
echo "       PID: $FRONTEND_PID, log: /tmp/niren-frontend.log"

echo ""
echo "[WAIT] Waiting for backend (usually 30-60s)..."

# ---- poll backend ----
for i in $(seq 1 60); do
    if netstat -ano 2>/dev/null | grep -q ":$BACKEND_PORT .*LISTENING"; then
        echo ""
        echo "[READY] Backend is up!"
        echo "       Frontend: http://localhost:$FRONTEND_PORT"
        echo "       Backend : http://localhost:$BACKEND_PORT/api/doc.html"
        echo "============================================"
        exit 0
    fi
    sleep 2
done

echo "[WARN] Backend did not start within 2 minutes. Check /tmp/niren-backend.log"
exit 1
