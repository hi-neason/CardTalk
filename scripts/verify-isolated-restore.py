"""Destructive only to UUID fixtures created by this run, never existing product data.
Requires an explicitly prepared loopback cardtalk_test database and cached JRE image.
No provider API keys, real-user content, or credentials are printed.
"""
from pathlib import Path
import os,subprocess,uuid,zipfile,time,json,urllib.request,urllib.parse,struct,zlib,secrets,tempfile,shutil
root=Path(__file__).resolve().parents[1];os.chdir(root);values={}
for line in Path(os.environ.get('CARDTALK_TEST_PG_ENV_FILE','.cardtalk/pgtest.env')).read_text().splitlines():
 if '=' in line and not line.startswith('#'):
  key,value=line.split('=',1);values[key]=value.strip().strip('\"').strip("'")
parsed=urllib.parse.urlparse(values['CARDTALK_TEST_PG_URL'].removeprefix('jdbc:'));
if parsed.scheme!='postgresql' or parsed.hostname not in {'127.0.0.1','localhost','::1'} or parsed.path!='/cardtalk_test':raise SystemExit('Requires the dedicated loopback cardtalk_test database; refusing other targets')
pgenv={**os.environ,'PGPASSWORD':values.get('CARDTALK_TEST_PG_PASSWORD','')}
base=['-h',parsed.hostname,'-p',str(parsed.port or 5432),'-U',values['CARDTALK_TEST_PG_USER']];database=parsed.path.lstrip('/')
def sql(text,db=database):subprocess.run(['psql',*base,'-d',db,'-v','ON_ERROR_STOP=1','-q'],input=text,text=True,env=pgenv,check=True,stdout=subprocess.DEVNULL)
schema='ct_restore_'+uuid.uuid4().hex;restoredb='ct_restored_'+uuid.uuid4().hex
containers=[];envfiles=[];created=False;source_created=False
libs=Path(tempfile.mkdtemp(prefix='restore-libs-',dir=root/'.cardtalk'));libs.chmod(0o755)
with zipfile.ZipFile(root/'backend/target/cardtalk-api-0.1.0.jar') as jar:
 for entry in jar.namelist():
  if entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
   dependency=libs/Path(entry).name;dependency.write_bytes(jar.read(entry));dependency.chmod(0o644)
image=os.environ.get('CARDTALK_RUNTIME_CHECK_IMAGE','docker.io/library/eclipse-temurin:21-jre')
def start(db,port):
 name='cardtalk-restore-check-'+uuid.uuid4().hex[:8]
 config={'PORT':'8080','CARDTALK_BIND_ADDRESS':'0.0.0.0','CARDTALK_FAMILY_REPOSITORY_MODE':'postgres','CARDTALK_FAMILY_JDBC_URL':'jdbc:'+parsed._replace(netloc='host.docker.internal:'+str(parsed.port or 5432),path='/'+db,query='currentSchema='+schema).geturl(),'CARDTALK_FAMILY_JDBC_USER':values['CARDTALK_TEST_PG_USER'],'CARDTALK_FAMILY_JDBC_PASSWORD':values.get('CARDTALK_TEST_PG_PASSWORD',''),'CARDTALK_TRIAL_REPOSITORY_MODE':'postgres','CARDTALK_REPORT_QUOTA_MODE':'postgres','CARDTALK_ASSET_METADATA_MODE':'postgres','CARDTALK_ASSET_DIRECTORY':'/data/assets','CARDTALK_CLOUD_ENABLED':'true','CARDTALK_BILLING_ENFORCED':'true','CARDTALK_SUPPORT_ENABLED':'true','CARDTALK_CONTENT_ENABLED':'true','CARDTALK_METRICS_ENABLED':'true','CARDTALK_LLM_API_KEY':'','CARDTALK_XFYUN_APP_ID':'','CARDTALK_BILLING_PROVIDER':'none'}
 file=root/'.cardtalk'/f'{name}.env';file.write_text('\n'.join(key+'='+value for key,value in config.items())+'\n');file.chmod(0o600);envfiles.append(file)
 result=subprocess.run(['docker','run','-d','--rm','--pull=never','--name',name,'--user','10001:10001','--workdir','/data','--tmpfs','/data:rw,uid=10001,gid=10001','-p',f'127.0.0.1:{port}:8080','--env-file',str(file),'-v',str(root/'backend/target/classes')+':/classes:ro','-v',str(libs)+':/libs:ro',image,'java','-cp','/classes:/libs/*','com.cardtalk.api.Application'],capture_output=True,text=True,check=True);containers.append(name)
 url=f'http://127.0.0.1:{port}/api/v1'
 for _ in range(150):
  try:
   with urllib.request.urlopen(url+'/health',timeout=1) as response:
    data=json.load(response)
    if data['llmConfigured'] is not False or data['ttsConfigured'] is not False:raise RuntimeError('Unexpected paid provider configuration')
    return name,url
  except Exception:time.sleep(.2)
 raise RuntimeError('Isolated container did not start')
def call(url,path,data=None,token=None,media='application/json'):
 headers={'Content-Type':media}
 if token:headers['Authorization']='Bearer '+token
 raw=(json.dumps(data).encode() if media=='application/json' and data is not None else data)
 with urllib.request.urlopen(urllib.request.Request(url+path,data=raw,headers=headers),timeout=20) as response:
  value=response.read();return json.loads(value) if 'json' in response.headers.get('Content-Type','') else value
try:
 sql('CREATE SCHEMA '+schema+';');source_created=True;name,url=start(database,18082)
 password=secrets.token_urlsafe(20);username='restore_'+uuid.uuid4().hex[:12]
 session=call(url,'/accounts/native/register',{'username':username,'password':password});token=session['token'];family=call(url,'/families',{'name':'隔离恢复测试'},token)
 def chunk(kind,data):return struct.pack('!I',len(data))+kind+data+struct.pack('!I',zlib.crc32(kind+data)&0xffffffff)
 png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',1,1,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\x11\x22\x33\xff'))+chunk(b'IEND',b'')
 asset=call(url,f'/families/{family["id"]}/assets?profileId=restore-picture',png,token,'image/png')
 if call(url,f'/families/{family["id"]}/assets/{asset["id"]}',token=token)!=png:raise RuntimeError('Source asset bytes mismatch')
 dump=root/'.cardtalk/restore-check.dump';subprocess.run(['pg_dump',*base,'-d',database,'-n',schema,'-Fc','-f',str(dump)],env=pgenv,check=True,capture_output=True);dump.chmod(0o600)
 archive=root/'.cardtalk/restore-check-assets.tar';result=subprocess.run(['docker','exec',name,'tar','-C','/data','-cf','-','assets'],capture_output=True,check=True);archive.write_bytes(result.stdout);archive.chmod(0o600)
 sql('CREATE DATABASE '+restoredb+';');created=True
 subprocess.run(['pg_restore',*base,'-d',restoredb,'--exit-on-error',str(dump)],env=pgenv,check=True,capture_output=True)
 restored,restoredurl=start(restoredb,18083)
 subprocess.run(['docker','exec','-i',restored,'tar','-C','/data','-xf','-'],input=archive.read_bytes(),check=True,capture_output=True)
 recovered=call(restoredurl,'/accounts/native/login',{'username':username,'password':password});families=call(restoredurl,'/families',token=recovered['token'])
 if not any(item['id']==family['id'] for item in families):raise RuntimeError('Restored family identity mismatch')
 if call(restoredurl,f'/families/{family["id"]}/assets/{asset["id"]}',token=recovered['token'])!=png:raise RuntimeError('Restored asset bytes mismatch')
 print('Isolated PostgreSQL dump/restore + local asset archive + restored authenticated HTTP byte check: PASS')
finally:
 for name in containers:subprocess.run(['docker','stop',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 shutil.rmtree(libs)
 for file in envfiles:file.unlink(missing_ok=True)
 if created:sql('DROP DATABASE '+restoredb+';')
 if source_created:subprocess.run(['psql',*base,'-d',database,'-v','ON_ERROR_STOP=1','-q'],input='DROP SCHEMA '+schema+' CASCADE;',text=True,env=pgenv,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
