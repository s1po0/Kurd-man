#!/usr/bin/env python3
"""Opt-in live test of one disposable Koorix DEVICE, never an owner's account.
No tokens, device secrets, reader content or provider keys are logged. The
created device and all its Koorix rows are deleted in finally.
"""
import json,secrets,uuid,urllib.request,urllib.error,time,sys
BASE='https://cbkddsztcivllpwzflkx.supabase.co'
PUBLIC='sb_publishable_vVtVNjubYllxuSnfyzcolA_qk034IwO'
checks=0

def call(path,body=None,token=None,method='POST'):
    headers={'apikey':PUBLIC,'Content-Type':'application/json'}
    headers['Authorization']='Bearer '+(token or PUBLIC)
    raw=None if body is None else json.dumps(body).encode()
    req=urllib.request.Request(BASE+path,data=raw,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=45) as response:
            text=response.read();return response.status,json.loads(text) if text else {}
    except urllib.error.HTTPError as failure:
        failure.read();return failure.code,{}

def check(condition,code):
    global checks
    if not condition:raise RuntimeError(code)
    checks+=1

def main():
    device={'device_id':str(uuid.uuid4()),'device_secret':secrets.token_hex(32)}
    session=None
    try:
        for attempt in range(2):
            status,session=call('/functions/v1/koorix-device-session',device)
            if status==200:break
            if attempt==0:time.sleep(2)
        check(status==200 and session.get('access_token') and session.get('user',{}).get('id'),'device_bootstrap')
        token=session['access_token'];uid=session['user']['id']
        def rpc(name,params={}):return call('/rest/v1/rpc/'+name,params,token)
        status,again=call('/functions/v1/koorix-device-session',device)
        check(status==200 and again.get('user',{}).get('id')==uid,'stable_device_identity')
        status,_=call('/functions/v1/koorix-device-session',{**device,'device_secret':secrets.token_hex(32)})
        check(status>=400,'wrong_device_credential_rejected')
        status,state=rpc('koorix_status_v2')
        check(status==200 and state.get('free_job_limit')==3 and state.get('free_page_limit')==60,'daily_limits')
        check(state.get('jobs_today')==0 and not state.get('premium'),'fresh_device_no_premium')
        status,public=call('/rest/v1/koorix_profiles?select=id,premium_until',None,None,'GET')
        check(status in (401,403) or status==200 and public==[],'anonymous_profiles_not_public')
        status,_=call('/rest/v1/koorix_profiles?id=eq.'+uid,{'premium_until':'2099-01-01T00:00:00Z'},token,'PATCH')
        status,state=rpc('koorix_status_v2');check(not state.get('premium'),'client_cannot_grant_premium')
        job=str(uuid.uuid4());status,grant=rpc('koorix_authorize_job',{'p_job_id':job,'p_pages':1})
        check(status==200 and grant.get('allowed') and grant.get('jobs_today')==1,'first_authorization')
        status,grant=rpc('koorix_authorize_job',{'p_job_id':job,'p_pages':1})
        check(status==200 and grant.get('allowed') and grant.get('reused') and grant.get('jobs_today')==1,'resume_not_charged_twice')
        status,grant=rpc('koorix_authorize_job',{'p_job_id':job,'p_pages':2})
        check(status==200 and not grant.get('allowed'),'saved_job_page_count_bound')
        rpc('koorix_finish_job',{'p_job_id':job})
        status,grant=rpc('koorix_authorize_job',{'p_job_id':job,'p_pages':1})
        check(status==200 and not grant.get('allowed'),'completed_permit_not_reusable')
        for _ in range(2):
            status,grant=rpc('koorix_authorize_job',{'p_job_id':str(uuid.uuid4()),'p_pages':1});check(status==200 and grant.get('allowed'),'remaining_authorization')
        status,grant=rpc('koorix_authorize_job',{'p_job_id':str(uuid.uuid4()),'p_pages':1})
        check(status==200 and not grant.get('allowed') and grant.get('jobs_today')==3,'fourth_job_denied')
        status,order=rpc('koorix_request_order',{'p_months':1})
        check(status==200 and order.get('status')=='pending' and order.get('payment_charged') is False and order.get('price_iqd')==5000,'honest_manual_order')
        status,repeat=rpc('koorix_request_order',{'p_months':1});check(status==200 and repeat.get('id')==order.get('id'),'no_duplicate_pending_order')
        status,_=rpc('koorix_admin_approve_order',{'p_order_id':order['id']});check(status>=400,'guest_cannot_approve_payment')
        status,_=rpc('koorix_admin_create_code',{'p_months':12});check(status>=400,'guest_cannot_issue_codes')
        status,_=call('/functions/v1/koorix-device-session',{**device,'confirm':'NO'},token,'DELETE');check(status==403,'delete_requires_confirmation')
        print(f'::notice::No-login live backend: {checks} contract/security checks passed; disposable device cleanup follows')
    except Exception as failure:
        # The UUID is not a credential; retain it for precise owner cleanup if
        # the service failed before it could return the temporary session.
        print('::error::Live device contract failed: '+str(failure))
        print('::notice::DISPOSABLE_DEVICE_ID='+device['device_id'])
        return 1
    finally:
        if session and session.get('access_token'):
            status,result=call('/functions/v1/koorix-device-session',{**device,'confirm':'DELETE_KOORIX_DEVICE'},session['access_token'],'DELETE')
            if status!=200 or not result.get('ok'):
                print('::error::Disposable device cleanup was not acknowledged')
                print('::notice::DISPOSABLE_DEVICE_ID='+device['device_id'])
                return 1
            print('::notice::Disposable Koorix device and its cloud data deleted successfully')
    return 0
if __name__=='__main__':sys.exit(main())
