from typing import Literal
from pydantic import BaseModel, ConfigDict, Field

class Strict(BaseModel):
    model_config = ConfigDict(extra='forbid')

class Hotel(Strict):
    id: str = Field(pattern=r'^[a-zA-Z0-9_-]{1,64}$')
    name: str = Field(min_length=1, max_length=100)
    timezone: str = 'Asia/Kolkata'
    default_language: str = 'en'
    supported_languages: list[str] = ['en', 'hi']
    operator_primary: str = Field(default='', pattern=r'^$|^\+[1-9][0-9]{6,14}$')
    operator_secondary: str = Field(default='', pattern=r'^$|^\+[1-9][0-9]{6,14}$')
    emergency_contact: str = ''
    taxi_number: str = Field(default='', pattern=r'^$|^\+[1-9][0-9]{6,14}$')
    taxi_mode: Literal['staff_request', 'dialer'] = 'staff_request'
    checkin_instructions: str = ''
    services: dict[str, str] = {}
    knowledge: dict[str, str] = {}
    connector: Literal['unconfigured', 'demo', 'rest', 'edge'] = 'unconfigured'
    config_version: int = Field(default=1, ge=1)

class Note(Strict):
    original_request: str = Field(min_length=1, max_length=4000)
    summary: str = Field(min_length=1, max_length=1000)
    guest_name: str = Field(default='', max_length=100)
    room_number: str = Field(default='', max_length=20)
    urgency: Literal['normal', 'urgent'] = 'normal'

class BookingLookup(Strict):
    reference: str = Field(min_length=3, max_length=100)
    surname: str = Field(min_length=2, max_length=100)

class BookingID(Strict):
    booking_id: str = Field(min_length=1, max_length=100)

class Search(Strict):
    query: str = Field(min_length=1, max_length=300)

class Empty(Strict):
    pass

class Taxi(Strict):
    destination: str = Field(min_length=1, max_length=300)
    pickup_time: str = Field(min_length=1, max_length=100)
    guest_name: str = Field(default='', max_length=100)
    room_number: str = Field(default='', max_length=20)

class Operator(Strict):
    reason: str = Field(default='Guest requested a person', max_length=500)

class GuestRequest(Note):
    category: str = Field(default='reception', max_length=100)

TOOL_MODELS = {
    'lookup_booking': BookingLookup, 'get_guest': BookingID,
    'get_checkin_status': BookingID, 'get_checkin_instructions': BookingID,
    'get_hotel_info': Empty, 'get_hotel_services': Empty,
    'search_hotel_knowledge': Search, 'leave_reception_note': Note,
    'call_taxi': Taxi, 'connect_operator': Operator,
    'notify_staff': GuestRequest, 'create_guest_request': GuestRequest,
}

def tool_definitions():
    return [{'type': 'function', 'name': name,
             'description': ('Request a human immediately.' if name == 'connect_operator' else
                 'Uses trusted hotel data. Writes require guest confirmation on the device.'),
             'parameters': cls.model_json_schema()} for name, cls in TOOL_MODELS.items()]
