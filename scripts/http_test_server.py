"""Native JVM HTTP harness using only the explicitly selected local test database."""
import base64
import http.client
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from test_database import test_environment

class HttpTestServer:
    def __init__(self, extra_arguments=()):
        self.root=Path(__file__).resolve().parents[1]
        jars=list((self.root/'target').glob('*.jar'))
        if len(jars)!=1: raise ValueError('Build exactly one verified application JAR first')
        self.jar=jars[0]
        self.env=test_environment(os.environ)
        username,password='runtime-'+str(uuid.uuid4()),secrets.token_urlsafe(24)
        self.env.update(SPRING_PROFILES_ACTIVE='local',SPRING_SECURITY_USER_NAME=username,SPRING_SECURITY_USER_PASSWORD=password)
        self.auth='Basic '+base64.b64encode(f'{username}:{password}'.encode()).decode()
        self.java=str(Path(self.env['JAVA_HOME'])/'bin/java') if self.env.get('JAVA_HOME') else 'java'
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0));self.port=sock.getsockname()[1]
        self.base=f'http://127.0.0.1:{self.port}'
        self.extra_arguments=list(extra_arguments)
        self.logdir=Path(tempfile.mkdtemp(prefix='rufit-runtime-'))
        self.process=None;self.log=None;self.opener=None;self.csrf=None
    def request(self,method,path,body=None,key=None):
        headers={'Authorization':self.auth}
        if method not in ('GET','HEAD'): headers[self.csrf['headerName']]=self.csrf['token']
        if key: headers['Idempotency-Key']=key
        data=json.dumps(body).encode() if body is not None else None
        if data is not None: headers['Content-Type']='application/json'
        with self.opener.open(urllib.request.Request(self.base+path,data=data,headers=headers,method=method),timeout=8) as response:
            raw=response.read()
            self.last_response_metadata={'status':response.status,'bytes':len(raw),'contentType':response.headers.get('Content-Type')}
            return response.status,json.loads(raw) if raw else None
    def start(self):
        if self.process is not None: raise RuntimeError('Server already started')
        self.log=(self.logdir/f'server-{time.time_ns()}.log').open('w')
        self.process=subprocess.Popen([self.java,'-jar',str(self.jar),f'--server.port={self.port}',
            f'--spring.datasource.url={self.env["DB_URL"]}','--spring.datasource.username=rufit_test',*self.extra_arguments],
            cwd=self.root,env=self.env,stdout=self.log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+60
        while time.monotonic()<deadline:
            if self.process.poll() is not None: raise RuntimeError(f'Server exited; logs: {self.logdir}')
            try:
                with urllib.request.urlopen(self.base+'/actuator/health',timeout=1) as response:
                    if response.status==200: break
            except (OSError,urllib.error.URLError,http.client.HTTPException): pass
            time.sleep(0.1)
        else: raise RuntimeError(f'Server startup timeout; logs: {self.logdir}')
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        _,self.csrf=self.request('GET','/api/v1/csrf')
    def stop(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.terminate()
                try: self.process.wait(timeout=20)
                except subprocess.TimeoutExpired: self.process.kill();self.process.wait(timeout=5)
            self.process=None
        if self.log is not None: self.log.close();self.log=None
    def restart(self): self.stop();self.start()
    def __enter__(self):
        try: self.start();return self
        except BaseException: self.stop();raise
    def __exit__(self,kind,value,traceback):
        self.stop()
        if kind is None: shutil.rmtree(self.logdir)
        else: print(f'Runtime failure logs: {self.logdir}')
