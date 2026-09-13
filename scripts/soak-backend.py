#!/usr/bin/env python3
"""Run repeated record lifecycles against a newly owned, disposable test DB and one JVM."""
import argparse
from datetime import datetime,timezone,timedelta
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import tempfile
import time
from urllib.error import HTTPError
import uuid
from http_test_server import HttpTestServer

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--duration-seconds',type=int,default=3600)
parser.add_argument('--interval-seconds',type=int,default=60)
parser.add_argument('--result',type=Path,default=Path(__file__).resolve().parents[1]/'target/runtime-soak-result.json')
args=parser.parse_args()
if not 30<=args.duration_seconds<=43200 or not 1<=args.interval_seconds<=300:
    parser.error('Duration must be 30..43200 seconds and interval 1..300 seconds')
stopping=False
def stop(signum,frame):
    global stopping
    stopping=True
signal.signal(signal.SIGTERM,stop)
signal.signal(signal.SIGINT,stop)
run_id=str(uuid.uuid4())
started=time.monotonic()
result={'runId':run_id,'runnerPid':os.getpid(),'status':'starting','startedAt':datetime.now(timezone.utc).isoformat(),
    'requestedDurationSeconds':args.duration_seconds,'intervalSeconds':args.interval_seconds,
    'completedCycles':0,'requests':0,'peakRequestMs':0,'peakJvmRssBytes':0,'rssSamples':[],'databaseCleanedUp':False}
args.result.parent.mkdir(parents=True,exist_ok=True)
def report():
    result['elapsedSeconds']=round(time.monotonic()-started,2)
    result['updatedAt']=datetime.now(timezone.utc).isoformat()
    temporary=args.result.with_suffix(args.result.suffix+'.tmp')
    temporary.write_text(json.dumps(result,indent=2)+'\n');temporary.replace(args.result)
def docker(*arguments,environment=None):
    return subprocess.run(['docker',*arguments],check=True,timeout=90,env=environment,
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True).stdout.strip()
def call(server,method,path,body=None,key=None,expected=200):
    before=time.monotonic()
    try: status,value=server.request(method,path,body,key)
    except HTTPError as error:
        status=error.code
        value=json.loads(error.read())
    finally:
        result['requests']+=1
        result['peakRequestMs']=round(max(result['peakRequestMs'],(time.monotonic()-before)*1000),2)
    if status!=expected: raise AssertionError(f'Unexpected HTTP status {status}; expected {expected}')
    return value
def finish_cycle(server,baseline):
    recovered=call(server,'GET','/api/v1/workouts/active')
    assert recovered==baseline,'Persisted active snapshot changed during idle interval'
    # Development-only CSRF sessions can expire during a long idle interval.
    server.csrf=call(server,'GET','/api/v1/csrf')
    finished=call(server,'POST',f'/api/v1/workouts/{baseline["id"]}/finish',{'version':baseline['version']})
    summary=call(server,'GET',f'/api/v1/workouts/{baseline["id"]}/summary')
    assert summary['completedSets']==2 and summary['totalReps']==13
    assert summary['volumeKgReps']==baseline['sets'][0]['weight']*5
    assert call(server,'GET','/api/v1/workouts')['totalElements']==1
    deletion={'version':finished['version'],'confirmed':True}
    call(server,'POST',f'/api/v1/workouts/{baseline["id"]}/delete',deletion,expected=204)
    call(server,'POST',f'/api/v1/workouts/{baseline["id"]}/delete',deletion,expected=204)
    call(server,'POST','/api/v1/workouts',key=baseline['id'],expected=410)
    assert call(server,'GET','/api/v1/workouts')['totalElements']==0
    assert call(server,'GET','/api/v1/workouts/active',expected=204) is None
    result['completedCycles']+=1
    rss=subprocess.run(['ps','-o','rss=','-p',str(server.process.pid)],check=True,capture_output=True,text=True)
    result['currentJvmRssBytes']=int(rss.stdout.strip())*1024
    result['peakJvmRssBytes']=max(result['peakJvmRssBytes'],result['currentJvmRssBytes'])
    elapsed=round(time.monotonic()-started,2)
    if not result['rssSamples'] or elapsed-result['rssSamples'][-1]['elapsedSeconds']>=300:
        result['rssSamples'].append({'elapsedSeconds':elapsed,'bytes':result['currentJvmRssBytes']})

report()
with tempfile.TemporaryDirectory(prefix='rufit-soak-') as directory:
    cidfile=Path(directory)/'container.id'
    failure=None
    try:
        password=secrets.token_urlsafe(32)
        docker_env={**os.environ,'POSTGRES_PASSWORD':password}
        docker('run','--detach','--cidfile',str(cidfile),'--name','rufit-soak-'+run_id,
            '--label','com.rufit.soak.run='+run_id,'--memory','512m','--publish','127.0.0.1::5432',
            '--env','POSTGRES_DB=rufit_test','--env','POSTGRES_USER=rufit_test','--env','POSTGRES_PASSWORD',
            'postgres:17',environment=docker_env)
        container=cidfile.read_text().strip()
        deadline=time.monotonic()+60
        while time.monotonic()<deadline:
            try:
                docker('exec',container,'pg_isready','-h','127.0.0.1','-U','rufit_test','-d','rufit_test')
                break
            except subprocess.CalledProcessError: time.sleep(0.2)
        else: raise RuntimeError('Isolated soak database did not become ready')
        mappings=json.loads(docker('inspect','--format','{{json .NetworkSettings.Ports}}',container))['5432/tcp']
        assert len(mappings)==1 and mappings[0]['HostIp']=='127.0.0.1'
        environment={**os.environ,'DB_URL':f'jdbc:postgresql://127.0.0.1:{mappings[0]["HostPort"]}/rufit_test',
            'DB_USERNAME':'rufit_test','DB_PASSWORD':password}
        with HttpTestServer(environment=environment) as server:
            digest=hashlib.sha256()
            with server.jar.open('rb') as jar:
                for chunk in iter(lambda:jar.read(1024*1024),b''): digest.update(chunk)
            result.update(status='running',jarSha256=digest.hexdigest(),jvmPid=server.process.pid)
            end=time.monotonic()+args.duration_seconds
            baseline=None
            while time.monotonic()<end and not stopping:
                if baseline is not None: finish_cycle(server,baseline)
                assert server.process.poll() is None,'JVM exited unexpectedly'
                server.csrf=call(server,'GET','/api/v1/csrf')
                key=str(uuid.uuid4())
                workout=call(server,'POST','/api/v1/workouts',key=key,expected=201)
                assert call(server,'POST','/api/v1/workouts',key=key,expected=201)['id']==workout['id']
                draft={'operationId':str(uuid.uuid4()),'version':workout['version'],
                    'sets':[{'set':{'exerciseName':'Soak squat','weight':20+result['completedCycles']%50,'reps':5},'completed':True},
                            {'set':{'exerciseName':'Soak push-up','weight':0,'reps':8},'completed':True}],
                    'restSetIndex':1,'restEndsAt':(datetime.now(timezone.utc)+timedelta(seconds=10)).isoformat(),
                    'note':'Runtime longevity test'}
                saved=call(server,'PUT',f'/api/v1/workouts/{key}/draft',draft)
                replay=call(server,'PUT',f'/api/v1/workouts/{key}/draft',draft)
                assert replay['replayed'] and replay['appliedVersion']==saved['appliedVersion']
                baseline=call(server,'GET','/api/v1/workouts/active')
                report()
                remaining=min(args.interval_seconds,max(0,end-time.monotonic()))
                until=time.monotonic()+remaining
                while time.monotonic()<until and not stopping: time.sleep(min(0.5,max(0,until-time.monotonic())))
            if baseline is not None: finish_cycle(server,baseline)
            result['status']='interrupted' if stopping else 'passed'
    except BaseException as error:
        result['status']='failed';result['failureType']=type(error).__name__;failure=error
    finally:
        if cidfile.exists():
            container=cidfile.read_text().strip()
            try:
                label=docker('inspect','--format','{{index .Config.Labels "com.rufit.soak.run"}}',container)
                if label!=run_id: raise RuntimeError('Refusing cleanup of an unowned container')
                docker('rm','--force','--volumes',container)
                result['databaseCleanedUp']=True
            except BaseException as error:
                result['status']='failed';result['cleanupFailureType']=type(error).__name__
                if failure is None: failure=error
        report()
    print(f'{result["status"].upper()}: {result["completedCycles"]} cycles, {result["requests"]} requests; result: {args.result}',flush=True)
    if failure is not None: raise failure
    if stopping: raise SystemExit(130)
