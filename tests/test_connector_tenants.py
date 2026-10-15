import pytest

from cloud.connectors import ConnectorUnavailable, RestConnector


HOTEL_NAMESPACES = {
    'hotel-a': 'PMS_686F74656C2D61',
    'hotel_a': 'PMS_686F74656C5F61',
    'HotelA': 'PMS_486F74656C41',
    'hotela': 'PMS_686F74656C61',
}


@pytest.mark.parametrize('configured_hotel', HOTEL_NAMESPACES)
def test_rest_credentials_are_exclusive_to_exact_hotel_id(monkeypatch, configured_hotel):
    for prefix in HOTEL_NAMESPACES.values():
        monkeypatch.delenv(prefix + '_URL', raising=False)
        monkeypatch.delenv(prefix + '_TOKEN', raising=False)
    prefix = HOTEL_NAMESPACES[configured_hotel]
    url = 'https://gateway.example/' + configured_hotel
    token = 'test-token-' + configured_hotel
    monkeypatch.setenv(prefix + '_URL', url)
    monkeypatch.setenv(prefix + '_TOKEN', token)

    configured = RestConnector(configured_hotel)
    assert configured.base == url
    assert configured.token == token
    for hotel in HOTEL_NAMESPACES:
        if hotel != configured_hotel:
            with pytest.raises(ConnectorUnavailable, match='PMS credentials not configured'):
                RestConnector(hotel)


def test_rest_legacy_colliding_names_never_supply_credentials(monkeypatch):
    for prefix in HOTEL_NAMESPACES.values():
        monkeypatch.delenv(prefix + '_URL', raising=False)
        monkeypatch.delenv(prefix + '_TOKEN', raising=False)
    for prefix in ('PMS_HOTEL_A', 'PMS_HOTELA'):
        monkeypatch.setenv(prefix + '_URL', 'https://legacy.example')
        monkeypatch.setenv(prefix + '_TOKEN', 'test-legacy-token')
    for hotel in HOTEL_NAMESPACES:
        with pytest.raises(ConnectorUnavailable, match='PMS credentials not configured'):
            RestConnector(hotel)
