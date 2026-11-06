#!/usr/bin/env python3
"""Read-only, value-redacting credential check of tracked public Git blobs.

This catches known private-key/provider-token formats, NOT every possible secret.
Public certificates, publishable keys and authenticated signing ciphertext are
intentionally allowed. No private working directories or credentials are read.
"""
import argparse, base64, json, re, subprocess, sys

PATTERNS = {
    'telegram_bot_token': re.compile(rb'(?<![\w-])[0-9]{7,12}:[A-Za-z0-9_-]{32,}(?![\w-])'),
    'github_token': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b'),
    'supabase_secret_key': re.compile(rb'\bsb_secret_[A-Za-z0-9_-]{20,}\b'),
    'google_oauth_client_secret': re.compile(rb'\bGOCSPX-[A-Za-z0-9_-]{20,}\b'),
    'private_key_material': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{80,}-----END'),
    'aws_access_key_id': re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
}
JWT = re.compile(rb'eyJ[A-Za-z0-9_-]{8,}\.([A-Za-z0-9_-]{8,})\.[A-Za-z0-9_-]{15,}')

def inspect(data):
    findings = []
    for kind, pattern in PATTERNS.items():
        for match in pattern.finditer(data):
            if kind == 'telegram_bot_token':
                ident, suffix = match.group().split(b':', 1)
                # A documented numeric example with a repeating A/a tail is
                # not an actual bot credential. Never exempt arbitrary tokens.
                if ident == b'123456789' and len(set(suffix)) <= 4:
                    continue
            findings.append(kind)
            break
    for match in JWT.finditer(data):
        try:
            payload = json.loads(base64.urlsafe_b64decode(match[1] + b'=' * (-len(match[1]) % 4)))
            if payload.get('role') == 'service_role':
                findings.append('supabase_service_role_jwt')
        except (ValueError, TypeError):
            pass
    return sorted(set(findings))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--history', action='store_true')
    args = parser.parse_args()
    command = ['git', 'rev-list', '--objects', '--all'] if args.history else ['git', 'ls-files', '-s']
    objects = {}
    for line in subprocess.check_output(command).splitlines():
        if args.history:
            oid, _, name = line.partition(b' ')
        else:
            fields, _, name = line.partition(b'\t')
            oid = fields.split()[1]
        objects.setdefault(oid, name)
    proc = subprocess.Popen(['git', 'cat-file', '--batch'], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    checked = 0
    findings = []
    for oid, name in objects.items():
        proc.stdin.write(oid + b'\n'); proc.stdin.flush()
        header = proc.stdout.readline().split()
        if len(header) != 3:
            continue
        size = int(header[2]); data = proc.stdout.read(size); proc.stdout.read(1)
        if header[1] != b'blob':
            continue
        checked += 1
        for kind in inspect(data):
            findings.append({'path':name.decode('utf-8','replace'), 'object':oid.decode(), 'kind':kind})
    proc.stdin.close(); proc.wait()
    print(json.dumps({'checked_blobs':checked, 'history':args.history, 'findings':findings,
        'limits':'Known credential formats only; no comprehensive secrecy guarantee.'}, indent=2))
    return bool(findings)
if __name__ == '__main__':
    sys.exit(main())
