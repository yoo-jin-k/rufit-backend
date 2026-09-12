#!/usr/bin/env python3
"""Restore only the dedicated rufit_test database into a disposable database and compare data."""
import json
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

root = Path(__file__).resolve().parents[1]
container = os.environ.get('RUFIT_TEST_CONTAINER', 'rufit-postgres-verify')
password = os.environ.get('DB_PASSWORD')
if not password:
    raise SystemExit('DB_PASSWORD for the dedicated rufit_test database is required')
# Source database and role are intentionally fixed. This is a test drill, not a production restore tool.
source_database = 'rufit_test'
restore_database = 'rufit_restore_test_' + uuid.uuid4().hex
env = {**os.environ, 'PGPASSWORD': password}
prefix = ['docker', 'exec', '-i', '--env', 'PGPASSWORD', '--env', 'PGCONNECT_TIMEOUT=5',
          '--env', 'PGOPTIONS=-c statement_timeout=60000 -c idle_in_transaction_session_timeout=180000', container]
connection = ['--host=127.0.0.1', '--username=rufit_test']

def psql(database):
    return prefix + ['psql', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '--dbname=' + database] + connection


def run(command, **kwargs):
    return subprocess.run(command, env=env, check=True, timeout=120, stderr=subprocess.PIPE, **kwargs)


fingerprint_sql = r"""
SELECT format($query$
    SELECT %L || ':' || count(*)::text || ':' ||
        md5(coalesce(string_agg(to_jsonb(t)::text, E'\n' ORDER BY to_jsonb(t)::text),''))
    FROM public.%I t
$query$, tablename, tablename)
FROM pg_tables WHERE schemaname='public' ORDER BY tablename
\gexec
SELECT '__RUFIT_FINGERPRINT_END__';
"""


def read_fingerprints(stream):
    lines = []
    while True:
        line = stream.readline()
        if not line:
            raise RuntimeError('Fingerprint session ended before completing all tables')
        line = line.strip()
        if line == '__RUFIT_FINGERPRINT_END__':
            return lines
        if not re.fullmatch(r'[a-z_]+:[0-9]+:[0-9a-f]{32}', line):
            raise RuntimeError('Unexpected database fingerprint output')
        lines.append(line)


created = False
holder = None
with tempfile.TemporaryDirectory(prefix='rufit-restore-drill-') as directory:
    directory = Path(directory)
    try:
        with (directory / 'snapshot-errors.log').open('w') as errors:
            holder = subprocess.Popen(psql(source_database), env=env, stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, stderr=errors, text=True, bufsize=1)
            holder.stdin.write("SET timezone='UTC'; BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT pg_export_snapshot();\n")
            holder.stdin.flush()
            snapshot = holder.stdout.readline().strip()
            if not re.fullmatch(r'[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9]+', snapshot):
                raise RuntimeError('Could not export a database snapshot')
            holder.stdin.write(fingerprint_sql)
            holder.stdin.flush()
            before = read_fingerprints(holder.stdout)
            counts = {line.split(':')[0]: int(line.split(':')[1]) for line in before}
            if counts.get('workouts', 0) < 1:
                raise RuntimeError('Run the HTTP restart smoke test first so the backup contains workout data')
            dump = directory / 'rufit-test.dump'
            with dump.open('wb') as output:
                run(prefix + ['pg_dump', '--format=custom', '--snapshot=' + snapshot,
                              '--dbname=' + source_database] + connection, stdout=output)
            holder.stdin.write('ROLLBACK;\n\\q\n')
            holder.stdin.flush()
            holder.wait(timeout=10)
            if holder.returncode != 0:
                raise RuntimeError('Snapshot session failed')
        run(psql('postgres'), input='CREATE DATABASE ' + restore_database + ' TEMPLATE template0;\n', text=True, stdout=subprocess.PIPE)
        created = True
        with dump.open('rb') as backup:
            run(prefix + ['pg_restore', '--exit-on-error', '--single-transaction', '--no-owner', '--no-privileges',
                          '--dbname=' + restore_database] + connection, stdin=backup, stdout=subprocess.PIPE)
        restored = run(psql(restore_database), input="SET timezone='UTC';\n" + fingerprint_sql, text=True, stdout=subprocess.PIPE)
        from io import StringIO
        after = read_fingerprints(StringIO(restored.stdout))
        if before != after:
            raise RuntimeError('Restored table row counts or content fingerprints differ from the backup snapshot')
        result = {'passed': True, 'checkedAt': datetime.now(timezone.utc).isoformat(), 'sourceDatabase': source_database, 'tables': counts,
                  'verifiedTableCount': len(counts), 'verifiedRowCount': sum(counts.values())}
    finally:
        if holder is not None and holder.poll() is None:
            holder.terminate()
            holder.wait(timeout=10)
        if created:
            # This generated name is the only database the script may remove.
            assert re.fullmatch(r'rufit_restore_test_[0-9a-f]{32}', restore_database)
            run(psql('postgres'), input='DROP DATABASE ' + restore_database + ' WITH (FORCE);\n', text=True, stdout=subprocess.PIPE)

(root / 'target').mkdir(exist_ok=True)
(root / 'target' / 'backup-restore-result.json').write_text(json.dumps(result, indent=2) + '\n')
print('PASS: database backup restored into an isolated database; %d tables and %d rows match; temporary database removed' % (len(counts), sum(counts.values())))
