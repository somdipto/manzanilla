# Protocol 1
Device credential identifies a hotel; clients never select a tenant in tools. POST /api/device/heartbeat returns tenant config; report version every 25 seconds. POST /api/device/session returns a single-use 30-second ticket. Send it in X-Orange-Ticket to WSS /ws/device. Binary audio is PCM16LE mono 24 kHz (max 9600 bytes per frame); unlike the legacy bridge it has no tag byte.

Server JSON: ready, state, audio_item, interrupt, confirm, handoff, error, ended. Device: stop, ping, confirmation {id,accepted}, handoff_result {id,status}, played {item_id,ms}. A confirmation ID belongs only to its current authenticated session and expires after 60 seconds. Backend validates arguments against Pydantic JSON schemas; model-supplied extra fields are rejected. Server exposes no arbitrary OpenAI event forwarding from device.

WSS /ws/edge uses a distinct role= edge Bearer token. Edge sends heartbeat every 25 seconds. Cloud execute {id,capability,args}; Edge result {id,result}. Only read_reservation is currently implemented. Cloud result matching checks both tenant and task ID and times out after 10 seconds. No inbound port is opened on the hotel PC.

Reservation gateway: POST https://configured-base/lookup-booking with reference,surname; bearer credential from PMS_<HOTEL_ID>_TOKEN. Return {id,guest_name,room_number,eligible:boolean,status}, or HTTP 404. This is a hotel-owned API contract, not an implemented Cloudbeds/Mews vendor connector. Reference + surname is low-assurance booking lookup, not sufficient authorization for room access, payment or reservation modification.
