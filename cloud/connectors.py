"""Vendor-independent boundary. REST adapter requires a hotel-owned gateway contract."""
import os
import httpx

class ConnectorUnavailable(Exception): pass

class DemoConnector:
    async def lookup(self, reference, surname):
        if reference == 'DEMO-204' and surname.casefold() == 'smith':
            return {'id':'demo-204','guest_name':'Alex Smith','room_number':'204',
                    'eligible': True, 'status':'arrival', 'demo': True}
        return None

class RestConnector:
    def __init__(self, hotel_id):
        prefix = 'PMS_' + hotel_id.upper().replace('-', '_')
        self.base = os.getenv(prefix + '_URL', '')
        self.token = os.getenv(prefix + '_TOKEN', '')
        if not self.base.startswith('https://') or not self.token:
            raise ConnectorUnavailable('PMS credentials not configured')
    async def lookup(self, reference, surname):
        # Fixed route, read-only, timeout, no redirect, no model-supplied URL.
        try:
            async with httpx.AsyncClient(timeout=8, follow_redirects=False) as client:
                res = await client.post(self.base.rstrip('/') + '/lookup-booking',
                    headers={'Authorization': 'Bearer ' + self.token},
                    json={'reference': reference, 'surname': surname})
                if res.status_code == 404: return None
                res.raise_for_status()
                data = res.json()
                if not isinstance(data.get('id'), str) or not isinstance(data.get('eligible'), bool):
                    raise ValueError('Invalid gateway result')
                return {k: data.get(k) for k in ('id','guest_name','room_number','eligible','status')}
        except (httpx.HTTPError, ValueError) as exc:
            raise ConnectorUnavailable('PMS unavailable') from exc

class EdgeConnector:
    def __init__(self, hid, execute): self.hid, self.execute = hid, execute
    async def lookup(self, reference, surname):
        result = await self.execute(self.hid, 'read_reservation', {'reference':reference,'surname':surname})
        if result.get('status') != 'ok': raise ConnectorUnavailable('Edge unavailable')
        return result.get('booking')

def connector(hotel, execute):
    if hotel['connector'] == 'demo': return DemoConnector()
    if hotel['connector'] == 'rest': return RestConnector(hotel['id'])
    if hotel['connector'] == 'edge': return EdgeConnector(hotel['id'], execute)
    raise ConnectorUnavailable('No reservation integration configured')
