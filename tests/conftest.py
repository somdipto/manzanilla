"""Keep application import-time schema initialization away from deployed data."""
import os
import tempfile
import pytest

_previous_db = os.environ.get('ORANGE_DB')
_bootstrap = tempfile.TemporaryDirectory(prefix='manzanilla-tests-')
os.environ['ORANGE_DB'] = os.path.join(_bootstrap.name,'bootstrap.db')
from cloud.app import store as _bootstrap_store

@pytest.fixture
def reservation_connector(monkeypatch):
    """Synthetic reservations exist only under explicit isolated test injection."""
    import cloud.gateway as gateway
    real_connector = gateway.connector

    class FixtureConnector:
        async def lookup(self, reference, surname):
            if reference == 'FIXTURE-204' and surname.casefold() == 'smith':
                return {'id':'fixture-204','guest_name':'Synthetic Guest','room_number':'204',
                        'eligible':True,'status':'arrival'}
            return None

    def fixture_connector(hotel, execute):
        if hotel['connector'] == 'rest': return FixtureConnector()
        return real_connector(hotel,execute)

    monkeypatch.setattr(gateway,'connector',fixture_connector)


def pytest_unconfigure(config):
    _bootstrap_store.db.close()
    if _previous_db is None:
        os.environ.pop('ORANGE_DB',None)
    else:
        os.environ['ORANGE_DB'] = _previous_db
    _bootstrap.cleanup()
