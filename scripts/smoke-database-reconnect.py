#!/usr/bin/env python3
"""Interrupt only the selected disposable PostgreSQL test container and verify reconnection."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.parse import urlparse
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
import uuid
from http_test_server import HttpTestServer
from test_database import test_environment

result_path=Path(__file__).resolve().parents[1]/'target/database-reconnect-result.json'
result_path.unlink(missing_ok=True)
env=test_environment(os.environ)
database=os.environ.get('RUFIT_TEST_CONTAINER','rufit-postgres-verify')
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*',database): raise ValueError('Expected a test container name or ID')
def docker(*arguments):
    return subprocess.run(['docker',*arguments],check=True,timeout=40,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True).stdout.strip()
# Check identity and the actual published port before allowing any interruption.
docker('exec',database,'sh','-c','test "$POSTGRES_DB" = "rufit_test" && test "$POSTGRES_USER" = "rufit_test"')
ports=json.loads(docker('inspect','--format','{{json .NetworkSettings.Ports}}',database))
port=urlparse(env['DB_URL'][5:]).port or 5432
if not any(int(mapping['HostPort'])==port for mapping in ports.get('5432/tcp',[]) or []):
    raise ValueError('Selected DB_URL must address the selected test container published port')
restore_required=False

def probe(server,kind,expected_status,expected_state):
    try:
        response=urlopen(server.base+'/actuator/health/'+kind,timeout=8)
    except HTTPError as error: response=error
    with response:
        assert response.code==expected_status
        assert json.loads(response.read())=={'status':expected_state}

def start_database():
    docker('start',database)
    deadline=time.monotonic()+40
    while time.monotonic()<deadline:
        try:
            docker('exec',database,'pg_isready','-h','127.0.0.1','-U','rufit_test','-d','rufit_test')
            return
        except subprocess.CalledProcessError: time.sleep(0.2)
    raise RuntimeError('Test database did not become ready again')

try:
    with HttpTestServer(['--spring.datasource.hikari.connection-timeout=1000','--spring.datasource.hikari.validation-timeout=500']) as server:
        pid=server.process.pid
        probe(server,'liveness',200,'UP')
        probe(server,'readiness',200,'UP')
        _,workout=server.request('POST','/api/v1/workouts',key=str(uuid.uuid4()))
        workout_id=workout['id']
        draft={'operationId':str(uuid.uuid4()),'version':workout['version'],
               'sets':[{'set':{'exerciseName':'Reconnect test','weight':25,'reps':8},'completed':True}],
               'restSetIndex':None,'restEndsAt':None,'note':'Before interruption'}
        _,saved=server.request('PUT',f'/api/v1/workouts/{workout_id}/draft',draft)
        pending={**draft,'operationId':str(uuid.uuid4()),'version':saved['workout']['version'],'note':'Retried after reconnection'}
        restore_required=True
        docker('stop','--time','10',database)
        probe(server,'liveness',200,'UP')
        probe(server,'readiness',503,'DOWN')
        errors=[]
        for method,path,body in [('GET','/api/v1/workouts/active',None),('PUT',f'/api/v1/workouts/{workout_id}/draft',pending)]*3:
            try: server.request(method,path,body)
            except HTTPError as error:
                problem=json.loads(error.read())
                if error.code!=503 or problem.get('code')!='STORAGE_UNAVAILABLE':
                    raise AssertionError(f'Expected explicit storage unavailable error, got {error.code}')
                errors.append(error.code)
            else: raise AssertionError('A DB operation unexpectedly succeeded while PostgreSQL was stopped')
        start_database();restore_required=False
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            try:
                recovery_status,recovered=server.request('GET','/api/v1/workouts/active')
                if not isinstance(recovered,dict): raise AssertionError(f'Unexpected recovery response: status={recovery_status}, metadata={server.last_response_metadata}')
                break
            except (HTTPError,URLError): time.sleep(0.2)
        else: raise RuntimeError('Application did not reconnect to PostgreSQL')
        assert server.process.pid==pid and server.process.poll() is None
        probe(server,'liveness',200,'UP')
        probe(server,'readiness',200,'UP')
        assert recovered['id']==workout_id and recovered['note']=='Before interruption'
        assert recovered['version']==saved['workout']['version'] and recovered['sets'][0]['weight']==25
        _,result=server.request('PUT',f'/api/v1/workouts/{workout_id}/draft',pending)
        assert result['workout']['note']=='Retried after reconnection'
        assert result['appliedVersion']==recovered['version']+1
        _,replayed=server.request('PUT',f'/api/v1/workouts/{workout_id}/draft',pending)
        assert replayed['replayed'] and replayed['appliedVersion']==result['appliedVersion']
        server.request('POST',f'/api/v1/workouts/{workout_id}/finish',{'version':result['workout']['version']})
        _,history=server.request('GET','/api/v1/workouts')
        assert history['totalElements']==1 and history['items'][0]['id']==workout_id
finally:
    if restore_required: start_database()
result_path.write_text(json.dumps({'verifiedAt':datetime.now(timezone.utc).isoformat(),'outageStatuses':errors,
    'sameJvm':True,'recordRecovered':True,'pendingOperationRetriedOnce':True,'databaseRunning':True,
    'livenessSurvivedOutage':True,'readinessRecovered':True,'probeDetailsHidden':True},indent=2)+'\n')
print('PASS: test DB outage returns explicit 503; same JVM reconnects, recovers record and retries draft exactly once')
