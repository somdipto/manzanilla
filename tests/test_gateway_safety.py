import asyncio
import json

import pytest

from cloud.db import Store
from cloud.gateway import Gateway
from cloud.models import Hotel
from cloud.hotel_answers import ContentApproval, approve_content, source_hash


TAXI = {'destination': 'Airport', 'pickup_time': 'Now'}


@pytest.fixture
def store(tmp_path):
    db = Store(str(tmp_path / 'gateway.db'))
    db.save_hotel(Hotel(id='hotel-a', name='Hotel A').model_dump())
    yield db
    db.db.close()


async def unavailable(*_):
    return {'status': 'unavailable'}


async def approve(*_):
    return True


def gateway(store, confirm=approve, handoff=None):
    return Gateway(store, 'hotel-a', 'session', unavailable, confirm, handoff)


@pytest.mark.parametrize('retry_id', ['owner', 'duplicate', 'new-call'])
@pytest.mark.parametrize('reopen', [False, True])
def test_cancel_during_confirmation_releases_all_pending_calls(store, retry_id, reopen):
    async def scenario():
        entered = asyncio.Event()
        waiting = asyncio.Event()

        async def confirm(*_):
            entered.set()
            await waiting.wait()
            return True

        first = gateway(store, confirm)
        task = asyncio.create_task(first.execute('call_taxi', TAXI, 'owner'))
        await entered.wait()
        assert (await gateway(store).execute('call_taxi', TAXI, 'duplicate'))['status'] == 'pending'
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not store.rows('SELECT * FROM items')
        assert not store.rows('SELECT * FROM calls')
        assert not store.rows('SELECT * FROM write_claims')

        # Store's legacy bootstrap must not resurrect a duplicate's abandoned claim.
        reopened = Store(store.db.execute('PRAGMA database_list').fetchone()[2]) if reopen else None
        try:
            active = reopened or store
            retry = gateway(active)
            receipt = await retry.execute('call_taxi', TAXI, retry_id)
            assert receipt['status'] == 'queued'
            for ident in ('owner', 'duplicate', 'new-call', 'later'):
                assert await retry.execute('call_taxi', TAXI, ident) == receipt
            assert len(active.rows('SELECT * FROM items')) == 1
        finally:
            if reopened:
                reopened.db.close()

    asyncio.run(scenario())


def test_cancelled_confirmation_preserves_completed_other_operations(store):
    async def scenario():
        receipt = await gateway(store).execute('call_taxi', TAXI, 'completed')
        entered = asyncio.Event()
        waiting = asyncio.Event()

        async def confirm(*_):
            entered.set()
            await waiting.wait()
            return True

        other = {**TAXI, 'destination': 'Station'}
        task = asyncio.create_task(gateway(store, confirm).execute('call_taxi', other, 'abandoned'))
        await entered.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert await gateway(store).execute('call_taxi', TAXI, 'completed') == receipt
        assert await gateway(store).execute('call_taxi', TAXI, 'alias') == receipt
        assert len(store.rows('SELECT * FROM items')) == 1
        assert len(store.rows('SELECT * FROM write_claims')) == 1

    asyncio.run(scenario())


def test_concurrent_and_completed_write_idempotency(store):
    async def scenario():
        entered = asyncio.Event()
        resume = asyncio.Event()
        confirmations = 0

        async def confirm(*_):
            nonlocal confirmations
            confirmations += 1
            entered.set()
            await resume.wait()
            return True

        first = gateway(store, confirm)
        second = gateway(store, confirm)
        task = asyncio.create_task(first.execute('call_taxi', TAXI, 'owner'))
        await entered.wait()
        for ident in ('owner', 'duplicate'):
            assert (await second.execute('call_taxi', TAXI, ident))['status'] == 'pending'
        resume.set()
        receipt = await task
        for ident in ('owner', 'duplicate', 'new-call'):
            assert await second.execute('call_taxi', TAXI, ident) == receipt
        conflict = await second.execute('call_taxi', {**TAXI, 'destination': 'Station'}, 'duplicate')
        assert conflict['status'] == 'denied'
        assert confirmations == 1
        assert len(store.rows('SELECT * FROM items')) == 1

    asyncio.run(scenario())


@pytest.mark.parametrize('tool,arguments', [('call_taxi', TAXI), ('connect_operator', {})])
def test_cancellation_during_handoff_keeps_uncertain_write_claim(store, tool, arguments):
    hotel = store.hotel('hotel-a')
    hotel.update(taxi_mode='dialer', taxi_number='+34910000000', operator_primary='+34910000001')
    store.save_hotel(hotel)

    async def scenario():
        entered = asyncio.Event()
        waiting = asyncio.Event()
        handoffs = 0

        async def handoff(*_):
            nonlocal handoffs
            handoffs += 1
            entered.set()
            await waiting.wait()
            return {'status': 'dialer_opened'}

        first = gateway(store, handoff=handoff)
        task = asyncio.create_task(first.execute(tool, arguments, 'owner'))
        await entered.wait()
        assert len(store.rows('SELECT * FROM items')) == 1
        assert (await gateway(store).execute(tool, arguments, 'duplicate'))['status'] == 'pending'
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert store.rows("SELECT result FROM calls WHERE call_id='owner'") == [{'result': None}]
        assert len(store.rows('SELECT * FROM write_claims')) == 1

        reopened = Store(store.db.execute('PRAGMA database_list').fetchone()[2])
        try:
            retry = gateway(reopened, handoff=handoff)
            for ident in ('owner', 'duplicate', 'new-call'):
                assert (await retry.execute(tool, arguments, ident))['status'] == 'pending'
            assert handoffs == 1
            assert len(reopened.rows('SELECT * FROM items')) == 1
        finally:
            reopened.db.close()

    asyncio.run(scenario())


@pytest.mark.parametrize('invalidate',['source','rendering','legacy','authorization'])
def test_cached_information_is_revalidated_without_rewriting_durable_calls(store,monkeypatch,invalidate):
    import cloud.hotel_answers as answers
    hotel=store.hotel('hotel-a'); hotel['knowledge']={'breakfast':'07:00–10:00'}; store.save_hotel(hotel)
    approval=ContentApproval(kind='knowledge',content_key='breakfast',language='en',
        source_hash=source_hash('knowledge','breakfast','07:00–10:00'),
        title='Breakfast',body='07:00–10:00')
    approve_content(store,'hotel-a',approval,authorize=lambda:None)
    async def forbidden(*_,**__):
        raise AssertionError('Cached or exact answers must not contact Gemini')
    monkeypatch.setattr(answers,'_gemini_json',forbidden)
    async def scenario():
        active=True
        # Explicit callback exercises revocation without creating a synthetic active
        # operation session; only this read-only information call requires it.
        g=Gateway(store,'hotel-a','facts',unavailable,approve,authorized=lambda:active)
        taxi=await gateway(store).execute('call_taxi',TAXI,'taxi')
        note_args={'original_request':'Exact guest message.','summary':'Exact guest message.'}
        note=await gateway(store).execute('leave_reception_note',note_args,'note')
        durable=store.rows("SELECT * FROM calls WHERE session='session' ORDER BY call_id")
        first=await g.execute('search_hotel_knowledge',{'query':'breakfast?'},'answer')
        assert first['matches']=={'Breakfast':'07:00–10:00'}
        if invalidate=='source':
            hotel['knowledge']['breakfast']='08:00–11:00'; store.save_hotel(hotel)
        elif invalidate=='rendering':
            approve_content(store,'hotel-a',approval.model_copy(update={'body':'Updated approved rendering.'}),
                            authorize=lambda:None)
        elif invalidate=='legacy':
            first.pop('fact_refs')
            store.run("UPDATE calls SET result=? WHERE session='facts' AND call_id='answer'",(json.dumps(first),))
        else:
            active=False
        replay=await g.execute('search_hotel_knowledge',{'query':'breakfast?'},'answer')
        assert replay['status']=='denied' if invalidate=='authorization' else replay['status']=='stale'
        assert replay.get('matches',{})=={}
        if invalidate!='authorization':
            persisted=json.loads(store.rows("SELECT result FROM calls WHERE session='facts' AND call_id='answer'")[0]['result'])
            assert persisted==replay
        assert await gateway(store).execute('call_taxi',TAXI,'taxi')==taxi
        assert await gateway(store).execute('leave_reception_note',note_args,'note')==note
        assert store.rows("SELECT * FROM calls WHERE session='session' ORDER BY call_id")==durable
        assert len(store.rows('SELECT * FROM items'))==2
    asyncio.run(scenario())


def test_legacy_hotel_info_cache_cannot_bypass_approved_knowledge(store):
    hotel=store.hotel('hotel-a')
    hotel['knowledge']={'private policy':'Unapproved hotel policy must not reach guests.'}
    store.save_hotel(hotel)
    async def scenario():
        g=gateway(store)
        current=await g.execute('get_hotel_info',{},'info')
        assert 'knowledge' not in current
        legacy={**current,'knowledge':hotel['knowledge']}
        store.run("UPDATE calls SET result=? WHERE session='session' AND call_id='info'",(json.dumps(legacy),))
        replay=await g.execute('get_hotel_info',{},'info')
        assert replay==current
        assert replay['name']=='Hotel A'
        assert json.loads(store.rows("SELECT result FROM calls WHERE call_id='info'")[0]['result'])==current
        assert not store.rows('SELECT * FROM approved_content')
        assert not store.rows('SELECT * FROM items')
    asyncio.run(scenario())
