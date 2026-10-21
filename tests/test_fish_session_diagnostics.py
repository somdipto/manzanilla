import json

import httpx
import pytest

from tools.fish_session_diagnostics import fetch_session, summarize


def message(role, turn, at):
    return {'type':'message','role':role,'turn_id':turn,'created_at':at,'content':'PRIVATE SYNTHETIC TEXT'}


def test_history_gaps_require_unique_provider_turns_and_never_expose_transcripts():
    history={'metadata':{'asr_model':'deepgram:nova-3','private':'SECRET','guest_language':'es'},'items':[
        message('assistant',0,'2026-10-21T12:00:00Z'),
        message('user',1,'2026-10-21T12:00:01Z'),
        {'type':'tool_result','latency_ms':3,'output':'SECRET'},
        message('assistant',1,'2026-10-21T12:00:03.125Z'),
        message('user',2,'2026-10-21T12:00:04Z'),
        message('assistant',2,'2026-10-21T12:00:05Z'),
        message('assistant',2,'2026-10-21T12:00:05.5Z'),
        message('user',3,'2026-10-21T12:00:06Z'),
        message('assistant',None,'2026-10-21T12:00:07Z'),
    ]}
    report=summarize(history)
    assert report['requested_configuration']=={'asr_model':'deepgram:nova-3','guest_language':'es'}
    assert report['ungrouped_messages']==1
    assert report['turns']==[
        {'turn_id':0,'user_messages':0,'assistant_messages':1,'record_gap_ms':None,'status':'no_user_message'},
        {'turn_id':1,'user_messages':1,'assistant_messages':1,'record_gap_ms':2125.0,'status':'record_timestamps_only'},
        {'turn_id':2,'user_messages':1,'assistant_messages':2,'record_gap_ms':None,'status':'ambiguous_multiple_messages'},
        {'turn_id':3,'user_messages':1,'assistant_messages':0,'record_gap_ms':None,'status':'no_assistant_message'},
    ]
    assert 'SECRET' not in json.dumps(report) and 'PRIVATE' not in json.dumps(report)
    assert report['measurement']=='provider_message_record_gap_not_speech_latency'


@pytest.mark.parametrize('start,end',[
    (None,None),('invalid','2026-10-21T12:00:03Z'),
    ('2026-10-21T12:00:03Z','2026-10-21T12:00:01Z'),
    ('2026-10-21T12:00:01','2026-10-21T12:00:03'),
])
def test_missing_invalid_reversed_or_unzoned_timestamps_are_not_a_measurement(start,end):
    report=summarize({'items':[message('user',1,start),message('assistant',1,end)]})
    assert report['turns'][0]['record_gap_ms'] is None
    assert report['turns'][0]['status']=='missing_or_invalid_timestamps'


def test_fetch_only_reads_requested_session_with_no_redirects(monkeypatch):
    monkeypatch.setenv('FISH_API_KEY','fixture-secret')
    requests=[]
    def upstream(request):
        requests.append(request)
        assert request.method=='GET'
        assert str(request.url)=='https://api.fish.audio/v1/agent/sessions/fixture-session'
        assert request.headers['Authorization']=='Bearer fixture-secret'
        return httpx.Response(200,json={'items':[]})
    real=httpx.Client
    def client(**kwargs):
        assert kwargs['follow_redirects'] is False
        return real(transport=httpx.MockTransport(upstream),**kwargs)
    monkeypatch.setattr(httpx,'Client',client)
    assert fetch_session('fixture-session')=={'items':[]}
    assert len(requests)==1
    with pytest.raises(ValueError): fetch_session('../wrong-host')
    assert len(requests)==1
