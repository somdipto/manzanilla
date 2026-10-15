"""Keep application import-time schema initialization away from deployed data."""
import os
import tempfile

_previous_db = os.environ.get('ORANGE_DB')
_bootstrap = tempfile.TemporaryDirectory(prefix='manzanilla-tests-')
os.environ['ORANGE_DB'] = os.path.join(_bootstrap.name,'bootstrap.db')


def pytest_unconfigure(config):
    if _previous_db is None:
        os.environ.pop('ORANGE_DB',None)
    else:
        os.environ['ORANGE_DB'] = _previous_db
    _bootstrap.cleanup()
