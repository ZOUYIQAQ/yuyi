#!/usr/bin/env bash
# 为专用模拟器生成本地HTTPS验收证书；证书只进入debug资源。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .qa/tls app/src/debug/res/raw
cat > .qa/tls/openssl.cnf <<'CONFIG'
[req]
distinguished_name=dn
x509_extensions=v3
prompt=no
[dn]
CN=SimulSub QA
[v3]
subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost
basicConstraints=critical,CA:TRUE
keyUsage=critical,keyCertSign,digitalSignature,keyEncipherment
CONFIG
openssl req -x509 -nodes -newkey rsa:2048 -keyout .qa/tls/key.pem -out .qa/tls/cert.pem -days 30 -config .qa/tls/openssl.cnf > .qa/tls/generation.log 2>&1
cp .qa/tls/cert.pem app/src/debug/res/raw/qa_local_ca.pem
printf '%s\n' '已生成本地验收证书；请重新构建debug测试包，再用项目虚拟环境运行tools/qa-translation-server.py。'
