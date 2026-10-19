def _play(sock: socket.socket, host: str, port: int, username: str, timeout: float) -> dict[str, object]:
    sock.sendall(make_handshake(host, port, 2))
    sock.sendall(encode_packet(0, _string(username)))
    compression_threshold: int | None = None
    login_uuid: str | None = None
    joined = False
    entity_id: int | None = None
    movement_sent = False
    client_settings_sent = False
    window_items_received = False
    click_sent = False
    click_acknowledged = False
    click_confirmation = "pending"
    click_window_id = 0
    click_action_number = 1
    coordinates: tuple[float, float, float] | None = None
    server_packet_ids: list[int] = []
    click_deadline: float | None = None
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and not click_acknowledged:
        now = time.monotonic()
        if click_sent and click_deadline is not None and now >= click_deadline:
            break
        remaining = deadline - now
        if click_deadline is not None:
            remaining = min(remaining, click_deadline - now)
        sock.settimeout(max(remaining, 0.001))
        try:
            packet_id, payload = _read_packet(sock, compression_threshold)
        except socket.timeout as error:
            break
        server_packet_ids.append(packet_id)
        if packet_id == 0x03 and login_uuid is None:
            compression_threshold, offset = decode_varint(payload)
            if offset != len(payload):
                raise ProtocolProbeError("Set Compression packet contains trailing data")
            continue
        if packet_id == 0x02 and login_uuid is None:
            login_uuid = _parse_login_success(payload, username)
            continue
        if packet_id == 0x01 and login_uuid is not None and not joined:
            entity_id = _parse_join_game(payload)
            joined = True
            continue
        if packet_id == 0x00 and login_uuid is None:
            reason, _ = _read_string(payload)
            raise ProtocolProbeError(f"server rejected login: {reason}")
        if packet_id == 0x00 and joined:  # Keep Alive
            keep_alive, _ = decode_varint(payload)
            sock.sendall(encode_packet(0x00, encode_varint(keep_alive), compression_threshold))
            continue
        if packet_id == 0x08 and joined and not movement_sent:
            if len(payload) != 8 * 3 + 4 * 2 + 1:
                raise ProtocolProbeError("player position/look packet is too short")
            x, y, z, _yaw, _pitch, _flags = struct.unpack(">dddffB", payload)
            coordinates = (x, y, z)
            x, y, z = coordinates
            sock.sendall(encode_packet(0x04, struct.pack(">ddd?", x, y, z, True), compression_threshold))
            sock.sendall(
                encode_packet(
                    0x15,
                    _string("en_US") + struct.pack(">bBBB", 8, 0, 1, 0),
                    compression_threshold,
                )
            )
            movement_sent = True
            client_settings_sent = True
            continue
        if packet_id == 0x30 and joined and not click_sent:
            if not payload:
                raise ProtocolProbeError("Window Items packet is empty")
            window_id = payload[0]
            if window_id == 0:
                window_items_received = True
                # Slot zero starts empty in the disposable survival inventory.
                # Clicking it with an empty item exercises transaction handling
                # without changing the player's inventory.
                click_payload = (
                    struct.pack(">BhBh", window_id, 0, 0, click_action_number)
                    + encode_varint(0)
                    + struct.pack(">h", -1)
                )
                sock.sendall(encode_packet(0x0E, click_payload, compression_threshold))
                click_sent = True
                click_window_id = window_id
                click_deadline = time.monotonic() + min(timeout, CLICK_SETTLE_TIMEOUT)
            continue
        if packet_id == 0x32 and click_sent:
            if len(payload) != 4:
                raise ProtocolProbeError("Confirm Transaction packet has an invalid size")
            window_id, action_number, accepted = struct.unpack(">Bh?", payload)
            if window_id != click_window_id or action_number != click_action_number:
                continue
            if not accepted:
                raise ProtocolProbeError("server rejected the protocol 47 inventory click")
            click_acknowledged = True
            click_confirmation = "accepted-confirmation"
    if login_uuid is None:
        raise ProtocolProbeError("protocol 47 login did not receive Login Success")
    if not joined:
        raise ProtocolProbeError("protocol 47 login did not receive Join Game")
    if not movement_sent:
        raise ProtocolProbeError("protocol 47 player movement packet was not sent")
    if not window_items_received or not click_sent:
        raise ProtocolProbeError("protocol 47 player inventory Window Items packet was not received")
    if not click_acknowledged:
        # Protocol 47 only sends Confirm Transaction when it rejects an action;
        # an accepted no-op click has no positive confirmation packet.
        click_confirmation = "not-rejected"
    return {
        "login_success": True,
        "login_uuid": login_uuid,
        "joined": joined,
        "entity_id": entity_id,
        "movement_sent": movement_sent,
        "client_settings_sent": client_settings_sent,
        "position": coordinates,
        "window_items_received": window_items_received,
        "inventory_click_sent": click_sent,
        "inventory_click_acknowledged": click_acknowledged,
        "inventory_click_accepted": click_confirmation != "rejected",
        "inventory_click_confirmation": click_confirmation,
        "inventory_window_id": click_window_id,
        "inventory_action_number": click_action_number,
        "server_packet_ids_seen": sorted({f"0x{packet_id:02x}" for packet_id in server_packet_ids}),
    }


def run_probe(host: str, port: int, timeout: float = DEFAULT_TIMEOUT, username: str = "WindyProbe") -> dict[str, object]:
    if timeout <= 0:

