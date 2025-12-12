#!/usr/bin/env bash
set -euo pipefail

# Mint a dev HS256 JWT for edge-service.
#
# Usage:
#   EDGE_AUTH_HS256_SECRET='dev-secret' ./scripts/mint-dev-jwt.sh
#   EDGE_AUTH_HS256_SECRET='dev-secret' ./scripts/mint-dev-jwt.sh --sub=user-1 --employer=EMP-1 --scope=payroll:read --minutes=60
#
# Outputs a JWT on stdout.

SUB="user-1"
EMPLOYER_ID=""
SCOPE=""
MINUTES="30"
ISSUER="us-payroll-platform-dev"
AUDIENCE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sub) SUB="$2"; shift 2;;
    --employer) EMPLOYER_ID="$2"; shift 2;;
    --scope) SCOPE="$2"; shift 2;;
    --minutes) MINUTES="$2"; shift 2;;
    --iss) ISSUER="$2"; shift 2;;
    --aud) AUDIENCE="$2"; shift 2;;
    -h|--help)
      sed -n '1,40p' "$0"; exit 0;;
    *)
      echo "Unknown arg: $1" >&2; exit 2;;
  esac
done

: "${EDGE_AUTH_HS256_SECRET:?EDGE_AUTH_HS256_SECRET must be set}"

python3 - <<'PY'
import os, json, time, hmac, hashlib, base64

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode('utf-8').rstrip('=')

secret = os.environ['EDGE_AUTH_HS256_SECRET'].encode('utf-8')
sub = os.environ.get('JWT_SUB', 'user-1')
iss = os.environ.get('JWT_ISS', 'us-payroll-platform-dev')
minutes = int(os.environ.get('JWT_MINUTES', '30'))
aud = os.environ.get('JWT_AUD', '')
employer = os.environ.get('JWT_EMPLOYER_ID', '')
scope = os.environ.get('JWT_SCOPE', '')

now = int(time.time())
payload = {
  'sub': sub,
  'iss': iss,
  'iat': now,
  'exp': now + minutes * 60,
}
if aud:
  payload['aud'] = aud
if employer:
  payload['employer_id'] = employer
if scope:
  payload['scope'] = scope

header = {'alg': 'HS256', 'typ': 'JWT'}

h = b64url(json.dumps(header, separators=(',',':')).encode('utf-8'))
p = b64url(json.dumps(payload, separators=(',',':')).encode('utf-8'))
msg = f"{h}.{p}".encode('utf-8')
sig = hmac.new(secret, msg, hashlib.sha256).digest()
print(f"{h}.{p}.{b64url(sig)}")
PY
